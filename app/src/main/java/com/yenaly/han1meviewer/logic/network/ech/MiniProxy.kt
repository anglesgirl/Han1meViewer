package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 专用 mini 本地代理。
 *
 * 背景：登录必须走 WebView（IP 不干净时会弹人机验证，原生对话框搞不定），
 * 但 WebView 发不出 ECH 请求（`shouldInterceptRequest` 还拿不到 POST body）。
 * 解法：WebView 只跟 `http://127.0.0.1:PORT/` 说话（明文 localhost，不会被墙），
 * 本代理把请求经 Conscrypt ECH 转发到真实目标。
 *
 * URL 格式：`http://127.0.0.1:8080/https://hanime1.me/login`
 *  - WebView 侧：`MiniProxy.proxyUrl("https://hanime1.me/login")`
 *  - 代理侧：取 path 去掉首个 `/` 即得目标 URL。
 *
 * Cookie：WebView 的 CookieManager 按 `127.0.0.1` 存取，无需跨域同步。
 * 代理会把响应 `Set-Cookie` 里的 `Domain=` / `Secure` 去掉（否则 WebView
 * 在 http://127.0.0.1 下存不进去），`SameSite=None` 降级为 `Lax`。
 *
 * 只服务 WebView 流量，不做通用代理。重定向不跟随（`followRedirects(false)`），
 * 让 WebView 自己看到 302，登录成功判定走原有的 `shouldOverrideUrlLoading(isRedirect)`。
 */
object MiniProxy {

    private const val TAG = "MiniProxy"

    /** 用户心智中的固定端口；被占用则回落到动态端口 */
    const val PREFERRED_PORT = 8080

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = port > 0

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mini-proxy").apply { isDaemon = true }
    }

    /** 经 Conscrypt ECH 转发的客户端：不跟随重定向、不管 Cookie（WebView 自己管） */
    private val proxyClient: OkHttpClient by lazy {
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

    /** WebView 侧取响应体用的直连客户端（只访问 localhost，不需要 ECH） */
    private val localClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun baseUrl(): String {
        ensureRunning()
        return "http://127.0.0.1:$port/"
    }

    /** 当前页面的目标站（如 https://hanime1.me）。相对路径资源（/css/x.css）靠它拼出完整 URL。 */
    @Volatile
    var currentTargetHost: String? = null
        private set

    /** 目标 URL → 走代理的 URL（同时记录目标站，供相对路径拼接用） */
    fun proxyUrl(targetUrl: String): String {
        updateTargetHost(targetUrl)
        return "${baseUrl()}$targetUrl"
    }

    /** 从完整目标 URL 提取 scheme+host，如 https://hanime1.me/login → https://hanime1.me */
    private fun updateTargetHost(targetUrl: String) {
        val m = Regex("^(https?://[^/]+)").find(targetUrl)
        if (m != null) currentTargetHost = m.groupValues[1]
    }

    fun isProxyUrl(url: String): Boolean =
        port > 0 && url.startsWith("http://127.0.0.1:$port/")

    /**
     * 从代理 URL 还原完整目标 URL。
     * - 含嵌入目标：直接还原（如 .../https://hanime1.me/login）
     * - 相对路径：用 [currentTargetHost] 拼接（如 .../css/style.css → https://hanime1.me/css/style.css）
     * 解析不出返回 null。
     */
    fun resolveTarget(proxyUrl: String): String? {
        if (!isProxyUrl(proxyUrl)) return null
        val path = proxyUrl.removePrefix("http://127.0.0.1:$port/")
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        val host = currentTargetHost ?: return null
        return "$host/$path"
    }

    /** 从代理 URL 还原目标 URL（仅含嵌入目标的；相对路径用 [resolveTarget]） */
    fun extractTarget(proxyUrl: String): String? {
        if (!isProxyUrl(proxyUrl)) return null
        val target = proxyUrl.removePrefix("http://127.0.0.1:$port/")
        return target.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    @Synchronized
    fun ensureRunning(): Int {
        if (!isRunning) start()
        return port
    }

    @Synchronized
    fun start(): Int {
        if (isRunning) return port
        val ss = try {
            ServerSocket(PREFERRED_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.w(TAG, "port $PREFERRED_PORT busy, fallback to random port: ${e.message}")
            ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        }
        serverSocket = ss
        port = ss.localPort
        running.set(true)
        pool.execute { acceptLoop(ss) }
        Log.i(TAG, "started on 127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = -1
        Log.i(TAG, "stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                pool.execute { handle(socket) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
            }
        }
    }

    // ---------- HTTP 解析/转发 ----------

    private data class ProxyRequest(
        val method: String,
        val targetUrl: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun handle(socket: Socket) {
        try {
            Log.i(TAG, "handle: ${socket.remoteSocketAddress}")
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val req = readRequest(input) ?: run {
                writeSimple(socket, 400, "bad request")
                return
            }
            Log.i(TAG, "request: ${req.method} ${req.targetUrl}")
            forward(socket, req)
        } catch (e: Exception) {
            Log.w(TAG, "handle failed: ${e.message}")
            try {
                writeSimple(socket, 502, "proxy error")
            } catch (_: Exception) {
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                val s = sb.toString()
                return if (s.endsWith("\r")) s.dropLast(1) else s
            }
            // 忽略孤立 \r（readLine 逻辑里 \r 只在 \n 前有意义）
            if (b != '\r'.code || prev != '\r'.code) sb.append(b.toChar())
            prev = b
        }
    }

    private fun readRequest(input: InputStream): ProxyRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        // path 形如 /https://hanime1.me/login；相对路径形如 /css/style.css（页面内 <link href="/css/..."> 解析而来）
        val stripped = rawPath.removePrefix("/")
        val targetUrl = if (stripped.startsWith("https://") || stripped.startsWith("http://")) {
            stripped
        } else {
            // 相对路径：用当前目标站拼接
            val host = currentTargetHost ?: return null
            "$host/$stripped"
        }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        val body: ByteArray = when {
            method == "GET" || method == "HEAD" -> ByteArray(0)
            headers["Transfer-Encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunked(input)
            else -> {
                val len = headers["Content-Length"]?.toIntOrNull() ?: 0
                if (len > 0) readExact(input, len) else ByteArray(0)
            }
        }
        return ProxyRequest(method, targetUrl, headers, body)
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len.coerceAtLeast(0))
        var remaining = len
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n == -1) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 读掉 trailer + 空行
                while (true) {
                    val t = readLine(input) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            out.write(readExact(input, size))
        }
        return out.toByteArray()
    }

    /** 不转发的 hop-by-hop 头 */
    private fun isHopHeader(name: String): Boolean = when (name.lowercase()) {
        "host", "connection", "proxy-connection", "keep-alive",
        "transfer-encoding", "content-length", "upgrade", "te", "trailer" -> true
        else -> false
    }

    private fun forward(socket: Socket, req: ProxyRequest) {
        Log.i(TAG, "forward -> ${req.targetUrl}")
        val builder = Request.Builder().url(req.targetUrl)
        for ((k, v) in req.headers) {
            if (!isHopHeader(k)) builder.addHeader(k, v)
        }
        val contentType = req.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaTypeOrNull()
        val body = if (req.body.isNotEmpty()) req.body.toRequestBody(contentType)
        else if (req.method == "POST" || req.method == "PUT" || req.method == "PATCH") {
            ByteArray(0).toRequestBody(contentType)
        } else null
        builder.method(req.method, body)

        try {
            val resp = proxyClient.newCall(builder.build()).execute()
            resp.use {
                Log.i(TAG, "forward <- ${it.code} ${req.targetUrl}")
                val respBody = it.body?.bytes() ?: ByteArray(0)
                // 先在内存里拼好整个响应头：任何一步出错都不写 socket，避免半截响应；
                // 状态行/头字段做 CRLF 消毒（上游脏数据会导致 WebView 报 net::ERR_INVALID_RESPONSE）
                val sb = StringBuilder()
                val code = it.code
                val msg = it.message.replace("\r", "").replace("\n", "").ifBlank { "OK" }
                sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n")
                for ((name, value) in it.headers) {
                    val cleanName = name.replace("\r", "").replace("\n", "").trim()
                    if (cleanName.isEmpty()) continue
                    val cleanValue = value.replace("\r", "").replace("\n", "")
                    val ln = cleanName.lowercase()
                    if (ln == "transfer-encoding" || ln == "content-length" ||
                        ln == "connection"
                    ) continue
                    if (ln == "set-cookie") {
                        sb.append("Set-Cookie: ").append(rewriteSetCookie(cleanValue)).append("\r\n")
                    } else {
                        sb.append(cleanName).append(": ").append(cleanValue).append("\r\n")
                    }
                }
                // content-encoding 原样透传：WebView 自己会发 Accept-Encoding，
                // OkHttp 的 BridgeInterceptor 只在请求没带该头时才透明解压；
                // WebView 带了该头时 body 是原始压缩字节，必须把 content-encoding
                // 透传回去让 WebView 自己解，否则直接显示压缩字节就是乱码。
                // Content-Length 按实际回写的字节数重算。
                sb.append("Content-Length: ").append(respBody.size).append("\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")

                try {
                    val out = socket.getOutputStream()
                    val writer = out.bufferedWriter(Charsets.ISO_8859_1)
                    writer.write(sb.toString())
                    writer.flush()
                    out.write(respBody)
                    out.flush()
                } catch (e: Exception) {
                    // 写响应失败：直接关 socket，不写半截响应；不 rethrow，
                    // 避免 handle() 再追加 502 造成双状态行
                    Log.e(TAG, "write response failed", e)
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "forward failed for ${req.targetUrl}", e)
            throw e
        }
    }

    /**
     * WebView 在 `http://127.0.0.1` 下收 cookie：去掉 `Domain=`（否则域名对不上
     * 存不进），去掉 `Secure`（http 下 Secure cookie 会被拒），
     * `SameSite=None` 降级为 `Lax`（None 必须配 Secure）。
     */
    internal fun rewriteSetCookie(raw: String): String {
        var s = raw
        s = s.replace(Regex("(?i);\\s*Domain=[^;]*"), "")
        s = s.replace(Regex("(?i);\\s*Secure(?=;|$)"), "")
        s = s.replace(Regex("(?i)SameSite=None"), "SameSite=Lax")
        return s.trim().trimEnd(';').trim()
    }

    private fun writeSimple(socket: Socket, code: Int, text: String) {
        val body = text.toByteArray()
        val w = socket.getOutputStream().bufferedWriter(Charsets.ISO_8859_1)
        w.write("HTTP/1.1 $code $text\r\n")
        w.write("Content-Length: ${body.size}\r\n")
        w.write("Connection: close\r\n\r\n")
        w.flush()
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
    }

    // ---------- WebView 接入 ----------

    /**
     * `WebViewClient.shouldOverrideUrlLoading` 里调：
     * https/http 导航改写走代理；返回 true 表示已接管（调用方 loadUrl 了代理 URL）。
     * 调用方如需先做登录成功判定，在调之前自己判断即可。
     */
    fun overrideUrlLoading(
        view: android.webkit.WebView,
        url: String,
    ): Boolean {
        if (isProxyUrl(url)) return false
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        view.loadUrl(proxyUrl(url))
        return true
    }

    /**
     * `WebViewClient.shouldInterceptRequest` 里调：子资源经本地代理取，
     * 返回 WebResourceResponse；非 http(s) 返回 null 走系统默认。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        // 目标 URL：代理 URL 先解析（嵌入目标或相对路径如 /css/style.css 都用 currentTargetHost 还原），
        // 普通 https URL 直接用；都不是则走系统默认
        val target = when {
            isProxyUrl(url) -> resolveTarget(url) ?: return null
            url.startsWith("https://") || url.startsWith("http://") -> url
            else -> return null
        }
        return try {
            val proxied = proxyUrl(target)
            val builder = Request.Builder().url(proxied)
            for ((k, v) in request.requestHeaders) {
                if (!isHopHeader(k)) builder.addHeader(k, v)
            }
            // 子资源 body 拿不到（WebView API 限制），主 frame 的 POST 直接走代理 URL 不受影响
            val method = request.method.uppercase()
            if (method != "GET" && method != "HEAD") builder.method(method, null)
            val resp = localClient.newCall(builder.build()).execute()
            val code = resp.code
            val headers = mutableMapOf<String, String>()
            for ((k, v) in resp.headers) {
                if (k.equals("Set-Cookie", ignoreCase = true)) continue // 子资源 cookie 由主链路处理
                headers[k] = v
            }
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                ?: "application/octet-stream"
            val stream: InputStream = resp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            // 注意：不 close resp，让 WebView 读完流；body stream 关闭时连接回收
            WebResourceResponse(mime, "utf-8", code, reason(code), headers, stream)
        } catch (e: Exception) {
            Log.w(TAG, "intercept failed for $url: ${e.message}")
            null
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }
}
