package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * WebView 子请求的唯一入口（`shouldInterceptRequest`）。
 *
 * WebView 里发起的图片/脚本/页面请求绕不过 WebView 自己的 TLS 栈，**无法注入 ECH**，
 * 所以受保护域名必须在这里接管：交给 [EchHttp.client] 发（Conscrypt + ECH），
 * 再翻译成 [WebResourceResponse] 还给 WebView。
 *
 * **为什么必须接管而不是放行**：放行（返回 null）等于让 WebView 用明文 SNI 去连，
 * 被墙域名当场暴露 —— 与用户定下的 fail-closed 直接冲突。
 * 所以这里**失败也返回 502 页面，绝不返回 null**。
 *
 * 局限（已知，另行处理）：`shouldInterceptRequest` 拿不到 POST 的 body，
 * 所以**非 GET 一律放行** —— WebView 的登录 POST 会走 WebView 自己的 TLS 栈。
 */
object HyWebViewHelper {

    private const val TAG = "HY-ECH-WEBVIEW"

    /**
     * 给 WebView 挂上非 GET 传输桥（[EchWebBridge]）。
     * **必须在 loadUrl 之前调用一次** —— JavaScriptInterface 只在页面加载前注册才对这个页面可见。
     */
    fun installWebView(webView: WebView) {
        runCatching {
            webView.addJavascriptInterface(EchWebBridge(webView), EchWebBridge.NAME)
        }.onFailure { Log.e(TAG, "挂载传输桥失败: ${it.message}") }
    }

    /**
     * 页面加载完成后注入 JS 桥（`onPageFinished` 里调用）。
     * 只对受保护域名注入，其余页面一概不碰。
     */
    fun injectBridge(webView: WebView, url: String?) {
        if (url == null || !EchHosts.isProtected(runCatching { java.net.URI(url).host ?: "" }.getOrDefault(""))) {
            return
        }
        runCatching {
            webView.evaluateJavascript(EchWebBridgeJs.script(HANIME_HOSTNAME), null)
        }.onFailure { Log.w(TAG, "注入传输桥失败: ${it.message}") }
    }

    /**
     * WebView 子请求专用客户端。
     * cookieJar 用 NO_COOKIES —— Cookie 由 CookieManager 统一注入/回写（与 WebView 同源），
     * 交给 OkHttp 管会和 WebView 打架。
     */
    private val client: OkHttpClient by lazy {
        ConscryptEch.install()
        OkHttpClient.Builder()
            .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
            .dns(EchDns())
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .addInterceptor(EchRetryInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 在 `WebViewClient.shouldInterceptRequest` 里调用。
     * @return 非受保护域名 / 非 GET 返回 null（放行，保持 WebView 原行为）；否则返回响应（失败时为 502）
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url?.host ?: return null
        val method = request.method ?: "GET"

        // 只接管受保护域名：其余域名保持 WebView 原行为（不干涉普通浏览）
        if (!EchHosts.isProtected(host)) return null
        // ⚠️ POST body 取不到，无法代发 —— 放行（登录 POST 的 ECH 方案另行处理）
        if (method != "GET") {
            Log.i(TAG, "放行非 GET（body 取不到）：$method $host")
            return null
        }
        if (!ConscryptEch.ready && !ConscryptEch.install()) {
            Log.w(TAG, "Conscrypt 未就绪，fail-closed：$host")
            return failClosed(host, "ECH 传输层未就绪")
        }

        var lastError = "unknown"
        repeat(2) { attempt ->
            try {
                val builder = Request.Builder().url(url).get()
                request.requestHeaders.forEach { (k, v) ->
                    // Cookie 交给 CookieManager/cookieJar 统一注入；Host、Content-Length 由 OkHttp 自己管
                    if (k.equals("Host", true) || k.equals("Content-Length", true)) return@forEach
                    if (k.equals("Cookie", true)) return@forEach
                    runCatching { builder.header(k, v) }
                }

                // 把 WebView 已持有的 Cookie 带上（与 WebView 登录态一致）
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                if (!cmCookie.isNullOrBlank()) builder.header("Cookie", cmCookie)

                client.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.bytes() ?: ByteArray(0)
                    val contentType = resp.header("Content-Type") ?: "text/html"
                    var mime = "text/html"
                    var charset = "utf-8"
                    contentType.split(";").forEachIndexed { i, part ->
                        if (i == 0) mime = part.trim().ifEmpty { "text/html" }
                        else if (part.trim().startsWith("charset=", true)) {
                            charset = part.trim().substringAfter("=").trim()
                        }
                    }

                    // Set-Cookie 同步回 CookieManager（WebView 后续请求才带得上登录态）
                    val cm = CookieManager.getInstance()
                    resp.headers.values("Set-Cookie").forEach { raw ->
                        // 去掉 Domain/Secure 限制，确保能落到 WebView 能读的域上
                        var fixed = raw.replace(Regex(";\\s*Domain=[^;]+", RegexOption.IGNORE_CASE), "")
                        fixed = fixed.replace(Regex(";\\s*Secure", RegexOption.IGNORE_CASE), "")
                        runCatching { cm.setCookie(url, fixed) }
                    }
                    runCatching { cm.flush() }

                    val headers = LinkedHashMap<String, String>()
                    resp.headers.names().forEach { n -> headers[n] = resp.headers.get(n) ?: "" }

                    Log.i(TAG, "webview $host -> ${resp.code} (${body.size}B, redirected=${resp.priorResponse != null})")
                    return WebResourceResponse(mime, charset, resp.code,
                        resp.message.ifEmpty { "OK" }, headers, ByteArrayInputStream(body))
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "webview $host 第 ${attempt + 1} 次失败：$lastError")
                if (attempt == 0) {
                    // ECH 配置可能过期：清一次缓存再试
                    EchDoh.invalidateEch(host)
                    runCatching { Thread.sleep(300) }
                }
            }
        }

        return failClosed(host, lastError)
    }

    /**
     * fail-closed：宁可这一页打不开，也不放行明文 SNI。
     * **绝不能返回 null** —— 那会让 WebView 用自己的 TLS 栈去连，等于前功尽弃。
     */
    private fun failClosed(host: String, reason: String): WebResourceResponse {
        Log.e(TAG, "fail-closed: $host ($reason)")
        val page = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:sans-serif;padding:24px;line-height:1.6">
<h3>连接失败</h3>
<p>无法安全地连接到 $host，已阻止本次访问。</p>
<p style="color:#888;font-size:13px">原因：${reason.replace("<", "&lt;")}</p>
<p style="color:#888;font-size:13px">可到「设置 - 网络」检查 DoH 配置后重试。</p>
</body></html>"""
        return WebResourceResponse("text/html", "utf-8", 502, "Bad Gateway",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(page.toByteArray(Charsets.UTF_8)))
    }
}
