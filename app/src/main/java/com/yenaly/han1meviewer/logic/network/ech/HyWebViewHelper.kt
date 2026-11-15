package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * WebView ECH 拦截器（co3 架构：引擎版）。
 *
 * 正确架构（参考 co3 的 CoWebViewHelper）：
 * 1. WebView 直接加载真实 URL（https://hanime1.me/login），不经过本地代理服务器
 * 2. `shouldInterceptRequest` 拦截请求，在进程内直接调用 ECH 引擎
 *    （Conscrypt+OkHttp），返回 WebResourceResponse
 * 3. POST 交给 JS 桥（EchWebBridge）：shouldInterceptRequest 拿不到 POST body
 * 4. Cookie 全部由 WebView 的 CookieManager 管理，域名是真实的，无需改写
 *
 * ECH 策略：仅使用目标 hostname 自身的 ECHConfig；ECH-required host 失败时不做普通 TLS
 * 回退。其他 host 可在 ECH 不可用时使用经 DoH 的普通 TLS，但永不交回 WebView 系统 DNS。
 */
object HyWebViewHelper {

    private const val TAG = "HyWebViewHelper"

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"

    private fun safeErrorSummary(error: Throwable?): String {
        val raw = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.isNotBlank() } }
            .firstOrNull() ?: error?.javaClass?.simpleName ?: "unknown"
        val redacted = raw
            .replace(Regex("""https?://[^\s"'<>]+"""), "<URL redacted>")
            .replace(Regex("""[?&][^\s"'<>]+"""), "?<query redacted>")
            .take(180)
        return redacted.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun echRequiredFailureResponse(error: Throwable?): WebResourceResponse {
        val details = safeErrorSummary(error)
        val page = "<!DOCTYPE html><html><body><h3>连接失败</h3>" +
            "<p>该主机要求 ECH；ECH 请求失败（$details）。请稍后重试。</p></body></html>"
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(page.toByteArray()),
        )
    }

    private fun networkFailureResponse(error: Throwable?): WebResourceResponse {
        val details = safeErrorSummary(error)
        val page = "<!DOCTYPE html><html><body><h3>连接失败</h3>" +
            "<p>请求失败（$details）。请稍后重试。</p></body></html>"
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(page.toByteArray()),
        )
    }

    /** 经 Conscrypt ECH 的客户端：不跟随重定向（手动处理），不管 Cookie（CookieManager 管） */
    private val echClient: OkHttpClient by lazy {
        ConscryptEch.install()
        OkHttpClient.Builder()
            .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
            .dns(EchDns())
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor(EchRetryInterceptor())
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 普通 TLS 客户端：走 DoH 解析并保留证书校验，仅用于非 ECH-required host。 */
    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(EchDns())
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 是否目标站域名（主站，用于日志区分） */
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
     * 只拦截 GET（POST 返回 null 交给 JS 桥）。
     * HTTPS 请求经 OkHttp+DoH 处理；ECH-required host 失败时 fail-closed，其他 host
     * 仅在 ECH 不可用时使用证书校验仍开启的普通 TLS。网络失败始终返回错误页，不回退系统 DNS。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url.host ?: return null
        val method = request.method ?: "GET"

        // POST 交给 JS 桥：shouldInterceptRequest 拿不到 POST body
        if (method == "POST") {
            Log.d(TAG, "POST passthrough to JS bridge: host=$host")
            return null
        }

        // 只处理 HTTPS（HTTP 无需 ECH，也不走 DoH，直接放行）
        if (!url.startsWith("https://", ignoreCase = true)) return null

        // 非 GET 方法（HEAD/PUT/DELETE 等）：仅非 required host 走 verified directClient（DoH）。
        if (method != "GET") {
            if (ConscryptEch.isEchRequired(host)) {
                Log.w(TAG, "non-GET blocked from direct TLS for ECH-required host=$host")
                return echRequiredFailureResponse(null)
            }
            Log.d(TAG, "non-GET $method via directClient (DoH): host=$host")
            return try {
                doGetDirect(url, request)?.let { toWebResourceResponse(url, it) }
            } catch (e: Exception) {
                Log.e(TAG, "direct $method failed for host=$host (${e.javaClass.simpleName})")
                networkFailureResponse(e)
            }
        }

        // 按实际 target-host HTTPS record 尝试 ECH；ECH_REJECTED 会在 ConscryptEch 中升级为 required。
        var lastError: Exception? = null
        var echRequired = ConscryptEch.isEchRequired(host)
        repeat(2) { attempt ->
            var fetchUrl = url
            var redirects = 0
            try {
                while (true) {
                    val resp = doGet(fetchUrl, request, useEch = true) ?: break
                    if (resp.code in 300..399 && redirects < 5) {
                        val loc = resp.header("Location")?.trim()?.ifEmpty { null }
                        if (loc == null) {
                            resp.close()
                            break
                        }
                        val next = resolveUrl(fetchUrl, loc)
                        val nextHost = runCatching { java.net.URI(next).host }.getOrNull()
                        if (nextHost == null || !isSameOrSubdomain(nextHost, host)) {
                            Log.d(TAG, "cross-site redirect, stop ECH fetch: host=${hostOf(next)}")
                            resp.close()
                            break
                        }
                        echRequired = echRequired || ConscryptEch.isEchRequired(nextHost)
                        resp.close()
                        fetchUrl = next
                        redirects++
                        continue
                    }
                    return toWebResourceResponse(fetchUrl, resp)
                }
            } catch (e: Exception) {
                lastError = e
                val failedHost = hostOf(fetchUrl)
                echRequired = echRequired || ConscryptEch.isEchRequired(failedHost)
                Log.w(TAG, "ECH GET attempt ${attempt + 1}/2 failed: host=$failedHost (${e.javaClass.simpleName})")
                EchTrace.event("ECH failure host=$failedHost: ${e.javaClass.simpleName}")
                if (attempt == 0) {
                    try { Thread.sleep(700) } catch (_: Exception) {}
                }
            }
        }
        if (echRequired || ConscryptEch.isEchRequired(host)) {
            Log.e(TAG, "ECH required for host=$host; refusing direct-TLS fallback (${lastError?.javaClass?.simpleName ?: "unknown"})")
            return echRequiredFailureResponse(lastError)
        }
        Log.w(TAG, "ECH failed, will try verified direct TLS via DoH: host=$host (${lastError?.javaClass?.simpleName ?: "unknown"})")

        // 非 required host 的 ECH 失败：普通 TLS 仍经 DoH 解析并保留默认证书校验。
        return try {
            doGetDirect(url, request)?.let { toWebResourceResponse(url, it) } ?: networkFailureResponse(lastError)
        } catch (e: Exception) {
            Log.e(TAG, "Direct GET failed for host=$host (${e.javaClass.simpleName})")
            EchTrace.event("verified TLS failed host=$host: ${e.javaClass.simpleName}")
            // 直连也失败：返回错误页（不交回 WebView，避免系统 DNS 污染）
            networkFailureResponse(e)
        }
    }

    /** 判断是否为相同域名或子域名 */
    private fun isSameOrSubdomain(host: String, base: String): Boolean {
        val h = host.lowercase()
        val b = base.lowercase()
        return h == b || h.endsWith(".$b")
    }

    /** 在进程内经 ECH 发送 GET，返回 OkHttp Response（调用方负责 close） */
    private fun doGet(url: String, request: WebResourceRequest, useEch: Boolean = true): okhttp3.Response? {
        val method = request.method ?: "GET"
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
            Log.d(TAG, "cookie send: ${cookie.length} chars to host=${hostOf(url)}")
        }

        val client = if (useEch) echClient else directClient
        val req = if (method == "GET") builder.get().build() else builder.method(method, null).build()
        val resp = client.newCall(req).execute()

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

    /** 直连 GET（走 DoH 拿 IP，不做 ECH）：用于 ECH 失败时的回退 */
    private fun doGetDirect(url: String, request: WebResourceRequest): okhttp3.Response? {
        return doGet(url, request, useEch = false)
    }

    /** OkHttp Response → WebResourceResponse（流式，不进内存，防 OOM） */
    private fun toWebResourceResponse(url: String, resp: okhttp3.Response): WebResourceResponse {
        val headers = LinkedHashMap<String, String>()
        for ((name, value) in resp.headers) {
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
        val code = if (resp.code in 300..399) 200 else resp.code
        val contentLength = resp.body?.contentLength() ?: -1
        Log.d(TAG, "ECH GET ok: $code ${contentLength}b host=${hostOf(url)}")
        // 流式：body.byteStream() 直接给 WebView，不经过内存缓冲
        // 包装一层，流关闭时顺带关闭 OkHttp Response 释放连接
        val bodyStream = resp.body?.byteStream()?.let { raw ->
            object : java.io.FilterInputStream(raw) {
                override fun close() {
                    try { super.close() } catch (_: Exception) {}
                    try { resp.close() } catch (_: Exception) {}
                }
            }
        } ?: java.io.ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse(
            mimeType, encoding, code, "OK",
            headers, bodyStream,
        )
    }

    private fun resolveUrl(base: String, location: String): String =
        runCatching { java.net.URI(base).resolve(location).toString() }.getOrNull() ?: location
}
