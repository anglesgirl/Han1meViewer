package com.yenaly.han1meviewer.logic.network.ech

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.han1meviewer.util.EchStats
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebView 非 GET 请求的原生传输层（JS 桥的落地端）。
 *
 * ## 为什么必须有它
 * `WebViewClient.shouldInterceptRequest` **拿不到 POST 的 body**（`WebResourceRequest`
 * 没有 body 字段，这是 Android WebView 自己的 API 限制），所以受保护域名上的
 * 表单提交 / 页面里的 fetch、XHR 只能放行 —— 放行就是让 WebView 用自己的 TLS 栈
 * 明文发出去，被墙域名的 SNI 当场暴露。Go 反代时代看不到这个缺口（本地端口能读到明文
 * body），换成 Conscrypt（进程内、无端口）之后它才冒出来。
 *
 * 补法：在受保护域名的页面里注入一小段 JS，把**非 GET** 请求转交到这里，
 * 由 [EchHttp] 那条 Conscrypt + ECH 通道代发。GET 不走这里 —— 它已经被
 * `shouldInterceptRequest` 接管了，两条路各管一半，不重叠。
 *
 * ## 语义边界（保证"页面逻辑一行不动"）
 * - 只接**受保护域名**（[isProtectedUrl]），其余一律不管，保持 WebView 原行为
 * - 只接**非 GET/HEAD**，GET 由 [HyWebViewHelper] 负责
 * - Cookie 由 `CookieManager` 统一注入/回写（与 WebView 同一个 cookie 视图），
 *   所以这里用 `CookieJar.NO_COOKIES`，靠网络拦截器逐跳读写 —— 重定向的中间跳
 *   也要能带上/落下 cookie，否则登录态会丢
 * - **fail-closed**：拿不到 ECH、解析失败、网络失败 → 返回 502 而不是回落明文。
 *   页面会看到一次失败（可重试），但 SNI 不会泄露。
 */
class EchWebBridge(private val webView: WebView) {

    /**
     * 页面内的 `fetch` / `XMLHttpRequest` 非 GET 请求。
     * 结果通过 `window.__echBridgeResolve(id, payloadJson)` 异步回给 JS。
     */
    @JavascriptInterface
    fun send(
        id: String,
        method: String,
        url: String,
        headersJson: String,
        bodyB64: String,
        pageUrl: String,
    ) {
        if (!isProtectedUrl(url)) {
            // 不该发生（JS 侧已按同一份名单过滤）；真发生了就原样拒绝，绝不放行
            resolve(id, errorPayload("非受保护域名，桥拒绝代发"))
            return
        }
        Thread {
            val payload = runCatching {
                val resp = execute(method, url, headersJson, bodyB64, pageUrl)
                resp.use {
                    val body = it.body?.bytes() ?: ByteArray(0)
                    Log.i(TAG, "bridge $method ${hostOf(url)} -> ${it.code} (${body.size}B)")
                    EchStats.event("webview_bridge_ok", mapOf("method" to method, "status" to it.code.toString()))
                    JSONObject()
                        .put("ok", true)
                        .put("status", it.code)
                        .put("statusText", it.message.ifEmpty { "OK" })
                        .put("url", it.request.url.toString())
                        .put("headers", JSONObject(headerMap(it)))
                        .put("bodyB64", Base64.encodeToString(body, Base64.NO_WRAP))
                }
            }.getOrElse { t ->
                Log.w(TAG, "bridge $method ${hostOf(url)} 失败（fail-closed）: ${t.message}")
                EchStats.event("webview_bridge_fail", mapOf("method" to method, "err" to (t.message ?: "unknown").take(80)))
                errorPayload(t.message ?: t.javaClass.simpleName)
            }
            resolve(id, payload)
        }.start()
    }

    /**
     * 表单提交（登录等）。与 fetch 分开是因为它的语义是**导航**：
     * 必须读到 302 的 `Location`，再让 WebView 去 GET 那个地址（GET 会被
     * [HyWebViewHelper] 接管走 ECH），而不是把返回的 HTML 直接渲染出来。
     */
    @JavascriptInterface
    fun postForm(url: String, bodyB64: String, pageUrl: String) {
        if (!isProtectedUrl(url)) {
            Log.w(TAG, "表单提交被拒（非受保护域名）：${hostOf(url)}")
            return
        }
        Thread {
            val result = runCatching {
                // followRedirects(false)：凭据挂在 302 的 Set-Cookie 上，跟随了就只剩最终 200
                val resp = formClient.newCall(buildRequest("POST", url, "{}", bodyB64, pageUrl)).execute()
                val location = resp.header("Location")
                resp.close()
                Log.i(TAG, "form POST ${hostOf(url)} -> location=$location")
                val t = if (location.isNullOrBlank()) url else absolutize(location, url)
                // 成功判据：拿到了跳转地址，且不是又跳回登录页（密码错时站点是 200 留在原页）
                val ok = !location.isNullOrBlank() && !location.lowercase().contains("login")
                t to ok
            }.getOrElse { t ->
                Log.w(TAG, "form POST ${hostOf(url)} 失败（fail-closed）: ${t.message}")
                EchStats.event("webview_form_fail", mapOf("err" to (t.message ?: "unknown").take(80)))
                null
            }
            val target = result?.first
            val success = result?.second == true
            webView.post {
                // 登录成功（302 落到非登录页）→ 复用 App 原有的登录完成逻辑；
                // ⚠️ 这一步不能省：表单被原生代发后，WebView 自己不会跳转，
                // 原先靠 shouldOverrideUrlLoading(isRedirect) 判成功的那套永远不会触发，
                // 表现就是"提交成功了但 App 没登录上"。
                if (success && onFormLoginSuccess != null) {
                    val cookie = runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull().orEmpty()
                    Log.i(TAG, "表单提交成功 → 交回 App 的登录完成逻辑（cookie ${cookie.length}B）")
                    onFormLoginSuccess?.invoke(cookie)
                } else {
                    // 没配回调（例如 CF 挑战页）：像浏览器一样回到真实页面，走 GET + ECH
                    runCatching { webView.loadUrl(target ?: pageUrl) }
                }
            }
        }.start()
    }

    /** JS 侧的观测日志（XHR 兜底、跳过接管等）落到 logcat，方便真机定位 */
    @JavascriptInterface
    fun log(msg: String) {
        Log.i(TAG, "[js] ${msg.take(300)}")
    }

    // ---------------------------------------------------------------- 内部实现

    private fun execute(
        method: String,
        url: String,
        headersJson: String,
        bodyB64: String,
        pageUrl: String,
    ) = client.newCall(buildRequest(method, url, headersJson, bodyB64, pageUrl)).execute()

    private fun buildRequest(
        method: String,
        url: String,
        headersJson: String,
        bodyB64: String,
        pageUrl: String,
    ): Request {
        // fail-closed：ECH 没就绪就不发
        if (!ConscryptEch.ready && !ConscryptEch.install()) {
            throw IOException("ECH 传输层未就绪（fail-closed）")
        }
        val bytes = if (bodyB64.isEmpty()) ByteArray(0) else Base64.decode(bodyB64, Base64.DEFAULT)
        val builder = Request.Builder().url(url)
        val body = if (method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE") {
            bytes.toRequestBody(null)
        } else null
        builder.method(method, body)

        var hasContentType = false
        runCatching {
            val hs = JSONObject(headersJson)
            hs.keys().forEach { k ->
                val v = hs.optString(k)
                // 这两个由 OkHttp 自己管；Cookie 由网络拦截器逐跳注入
                if (k.equals("Host", true) || k.equals("Content-Length", true)) return@forEach
                if (k.equals("Cookie", true)) return@forEach
                if (k.equals("Content-Type", true)) hasContentType = true
                runCatching { builder.header(k, v) }
            }
        }
        if (body != null && !hasContentType) {
            builder.header("Content-Type", FORM_TYPE)
        }
        // 浏览器会自动带、原生代发必须自己补：缺 Origin/Referer 会被站点当成无效请求
        if (pageUrl.isNotEmpty() && pageUrl.startsWith("http")) {
            builder.header("Referer", pageUrl)
            originOf(pageUrl)?.let { if (!headersJson.contains("\"Origin\"", true)) builder.header("Origin", it) }
        }
        return builder.build()
    }

    private fun resolve(id: String, payload: JSONObject) {
        val js = "window.__echBridgeResolve(${JSONObject.quote(id)}, ${JSONObject.quote(payload.toString())});"
        webView.post { runCatching { webView.evaluateJavascript(js, null) } }
    }

    private fun errorPayload(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("status", 502)
        .put("error", message)
        .put("bodyB64", Base64.encodeToString(
            "ECH 桥拒绝以明文发送该请求：$message".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))

    private fun headerMap(resp: okhttp3.Response): Map<String, String> =
        resp.headers.names().associateWith { resp.headers.get(it) ?: "" }

    companion object {

        const val NAME = "EchBridge"
        private const val TAG = "HY-ECH-BRIDGE"
        private const val FORM_TYPE = "application/x-www-form-urlencoded"

        /**
         * 表单登录成功后的回调（参数：CookieManager 里该域的 cookie 串）。
         * LoginActivity 用它复用页面**既有**的登录完成逻辑（存 cookie + setResult + finish），
         * 不注册时退化成"像浏览器一样跳到 Location"（CF 挑战页就是这种）。
         * ⚠️ Activity 销毁时要置空，否则会持有已销毁的 Activity。
         */
        @Volatile
        var onFormLoginSuccess: ((String) -> Unit)? = null

        fun isProtectedUrl(url: String): Boolean = runCatching {
            val host = java.net.URI(url).host?.lowercase() ?: return false
            HANIME_HOSTNAME.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)

        private fun hostOf(url: String): String = runCatching {
            java.net.URI(url).host ?: url
        }.getOrDefault(url)

        private fun originOf(url: String): String? = runCatching {
            val u = java.net.URI(url)
            if (u.scheme == null || u.host == null) null
            else "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
        }.getOrNull()

        private fun absolutize(location: String, base: String): String =
            if (location.startsWith("http")) location
            else runCatching { java.net.URI(base).resolve(location).toString() }.getOrDefault(base)

        /**
         * 逐跳读写 `CookieManager` 里那份唯一的 cookie 视图。
         * ⚠️ 必须用**网络拦截器**（不是应用拦截器）：跟随重定向时每一跳都是一次
         * 独立的 exchange，只有这样登录 302 的 Set-Cookie 才落得下来。
         */
        private fun cookieSync(client: OkHttpClient.Builder): OkHttpClient.Builder =
            client.addNetworkInterceptor { chain ->
                val req = chain.request()
                val fromWebView = runCatching {
                    CookieManager.getInstance().getCookie(req.url.toString())
                }.getOrNull()
                val request = if (fromWebView.isNullOrBlank()) req
                else req.newBuilder().header("Cookie", fromWebView).build()

                val resp = chain.proceed(request)
                val cm = CookieManager.getInstance()
                resp.headers.values("Set-Cookie").forEach { raw ->
                    // 去 Domain/Secure 限制、SameSite 归一化，确保 WebView 侧也收得下
                    var fixed = raw.replace(Regex(";\\s*Domain=[^;]+", RegexOption.IGNORE_CASE), "")
                    fixed = fixed.replace(Regex(";\\s*Secure", RegexOption.IGNORE_CASE), "")
                    fixed = fixed.replace(Regex(";\\s*SameSite=[^;]+", RegexOption.IGNORE_CASE), "; SameSite=Lax")
                    runCatching { cm.setCookie(req.url.toString(), fixed) }
                }
                runCatching { cm.flush() }
                resp
            }

        /** fetch/XHR：跟随重定向（浏览器的默认语义），cookie 由拦截器逐跳同步 */
        private val client: OkHttpClient by lazy {
            ConscryptEch.install()
            cookieSync(
                OkHttpClient.Builder()
                    .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
                    .dns(EchDns())
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    .followRedirects(true)
                    .addInterceptor(EchRetryInterceptor())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
            ).build()
        }

        /** 表单提交：**不跟随重定向**，这样才能读到 302 与它的 Location/Set-Cookie */
        private val formClient: OkHttpClient by lazy {
            ConscryptEch.install()
            cookieSync(
                OkHttpClient.Builder()
                    .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
                    .dns(EchDns())
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    .followRedirects(false)
                    .addInterceptor(EchRetryInterceptor())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
            ).build()
        }
    }
}
