package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * WebView ECH 拦截器（co3 架构：引擎版）。
 *
 * 正确架构（参考 co3 的 CoWebViewHelper）：
 * 1. WebView 直接加载真实 URL（https://hanime1.me/login），不经过本地代理服务器
 * 2. `shouldInterceptRequest` 拦截 GET 请求，在进程内直接调用 ECH 引擎
 *    （Conscrypt+OkHttp），返回 WebResourceResponse
 * 3. POST 交给 JS 桥（EchWebBridge）：shouldInterceptRequest 拿不到 POST body
 * 4. Cookie 全部由 WebView 的 CookieManager 管理，域名是真实的，无需改写
 *
 * 失败语义：目标站域名一律 fail-closed（502），绝不放行明文 SNI；
 * 非目标域名如实交回 WebView（返回 null）。
 */
object HyWebViewHelper {

    private const val TAG = "HyWebViewHelper"

    /** 经 Conscrypt ECH 的客户端：不跟随重定向（手动处理），不管 Cookie（CookieManager 管） */
    private val echClient: OkHttpClient by lazy {
        ConscryptEch.install()
        OkHttpClient.Builder()
            .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
            .dns(EchDns())
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 是否目标站域名 */
    fun isTargetHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return HANIME_URL.any { base ->
            val domain = base.removePrefix("https://").removePrefix("http://").trimEnd('/')
            h == domain || h.endsWith(".$domain")
        }
    }

    /**
     * shouldInterceptRequest 唯一入口。
     * 只拦截 GET（POST 返回 null 交给 JS 桥）；只拦截目标站域名。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url.host ?: return null
        val method = request.method ?: "GET"

        // POST 交给 JS 桥：shouldInterceptRequest 拿不到 POST body
        if (method != "GET") {
            if (method == "POST") Log.d(TAG, "POST passthrough to JS bridge: $url")
            return null
        }

        // 非目标站域名直接放行
        if (!isTargetHost(host)) return null

        var lastError: Exception? = null
        repeat(2) { attempt ->
            var fetchUrl = url
            var redirects = 0
            try {
                while (true) {
                    val resp = doGet(fetchUrl, request) ?: break
                    // 手动跟随重定向（WebResourceResponse 不允许 3xx）
                    if (resp.code in 300..399 && redirects < 5) {
                        val loc = resp.header("Location")?.trim()?.ifEmpty { null }
                        if (loc == null) {
                            Log.w(TAG, "redirect without Location: $fetchUrl")
                            break
                        }
                        val next = resolveUrl(fetchUrl, loc)
                        val nextHost = runCatching { java.net.URI(next).host }.getOrNull()
                        if (nextHost == null || !isTargetHost(nextHost)) {
                            // 跨站重定向交回 WebView（非错误，不重试）
                            Log.d(TAG, "cross-site redirect, passthrough: $next")
                            return null
                        }
                        Log.d(TAG, "follow redirect: $fetchUrl -> $next")
                        fetchUrl = next
                        redirects++
                        continue
                    }
                    return toWebResourceResponse(fetchUrl, resp)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "ECH GET attempt ${attempt + 1}/2 failed: $url: ${e.message}")
                if (attempt == 0) {
                    // 第一次失败，等 700ms 再试一次（给 DoH/ECH 配置一点时间）
                    try {
                        Thread.sleep(700)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // 两次都失败才 fail-closed
        Log.w(TAG, "ECH GET failed after retry (fail-closed): $url: ${lastError?.message}")

        // 目标站 fail-closed：宁可失败也不明文直连
        val page = "<!DOCTYPE html><html><body><h3>ECH 连接失败</h3></body></html>"
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(page.toByteArray()),
        )
    }

    /** 在进程内经 ECH 发送 GET，返回 OkHttp Response（调用方负责 close） */
    private fun doGet(url: String, request: WebResourceRequest): okhttp3.Response? {
        val builder = Request.Builder().url(url)

        // 透传 WebView 的请求头（Cookie/Host 单独处理）
        request.requestHeaders.forEach { (k, v) ->
            if (k.equals("Cookie", ignoreCase = true)) return@forEach
            if (k.equals("Host", ignoreCase = true)) return@forEach
            if (k.contains('\n') || k.contains('\r')) return@forEach
            builder.header(k, v)
        }

        // Cookie 全部走 CookieManager（真实域名，无需改写）
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrEmpty()) {
            builder.header("Cookie", cookie)
            Log.d(TAG, "cookie send: ${cookie.length} chars to $url")
        }

        val resp = echClient.newCall(builder.get().build()).execute()

        // Set-Cookie 写回 CookieManager（真实域名，属性改写保证 WebView 能存下）
        resp.headers("Set-Cookie").forEach { raw ->
            var fixed = raw
            fixed = fixed.replace(Regex("(?i);\\s*Domain=[^;]+"), "")
            fixed = fixed.replace(Regex("(?i);\\s*Secure(?=;|$)"), "")
            fixed = fixed.replace(Regex("(?i);\\s*SameSite=[^;]+"), "; SameSite=Lax")
            runCatching { CookieManager.getInstance().setCookie(url, fixed) }
            Log.d(TAG, "set-cookie recv: ${raw.substringBefore(';').take(40)}")
        }
        runCatching { CookieManager.getInstance().flush() }

        // 5xx 视为可重试失败
        if (resp.code in 500..599) {
            resp.close()
            throw java.io.IOException("upstream ${resp.code}")
        }
        return resp
    }

    /** OkHttp Response → WebResourceResponse */
    private fun toWebResourceResponse(url: String, resp: okhttp3.Response): WebResourceResponse {
        resp.use {
            val body = it.body?.bytes() ?: ByteArray(0)
            val headers = LinkedHashMap<String, String>()
            for ((name, value) in it.headers) {
                when {
                    name.equals("Set-Cookie", ignoreCase = true) -> Unit // 已写回 CookieManager
                    name.equals("Content-Length", ignoreCase = true) -> Unit
                    name.equals("Transfer-Encoding", ignoreCase = true) -> Unit
                    name.isNotEmpty() -> headers[name] = value
                }
            }
            val contentType = headers.entries
                .firstOrNull { e -> e.key.equals("Content-Type", ignoreCase = true) }
                ?.value ?: "text/html"
            var mimeType = "text/html"
            var encoding = "utf-8"
            contentType.split(";").forEachIndexed { idx, part ->
                if (idx == 0) mimeType = part.trim().ifEmpty { "text/html" }
                else if (part.trim().startsWith("charset=", ignoreCase = true)) {
                    encoding = part.trim().substringAfter("=").trim()
                }
            }
            // 3xx 兜底：跳数用完或无 Location，包装成 200 避免 WebView 抛异常
            val code = if (it.code in 300..399) 200 else it.code
            Log.d(TAG, "ECH GET ok: $code ${body.size}b $url")
            return WebResourceResponse(
                mimeType, encoding, code, "OK",
                headers, ByteArrayInputStream(body),
            )
        }
    }

    private fun resolveUrl(base: String, location: String): String =
        runCatching { java.net.URI(base).resolve(location).toString() }.getOrNull() ?: location
}
