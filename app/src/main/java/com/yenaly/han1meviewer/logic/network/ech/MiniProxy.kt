package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 专用 mini 本地反向代理。
 *
 * 背景：登录必须走 WebView（IP 不干净时会弹人机验证，原生对话框搞不定），
 * 但 WebView 发不出 ECH 请求（`shouldInterceptRequest` 还拿不到 POST body）。
 * 解法：WebView 只跟 `http://127.0.0.1:PORT/` 说话（明文 localhost，不会被墙），
 * 本代理把请求经 Conscrypt ECH 转发到真实目标站。
 *
 * 反向代理模式（对齐老 Go 版做法）：
 *  - 代理在启动时记住目标站 [targetBase]（如 `https://hanime1.me`），
 *    URL 里不再嵌套完整目标。
 *  - WebView 打开 `http://127.0.0.1:8080/login`，服务端收到 path `/login`，
 *    直接拼出 `https://hanime1.me/login` 再转发。
 *  - 页面内的相对路径（`/css/style.css`、表单 action 等）天然落在代理域名下，
 *    无需 `shouldInterceptRequest` 拦截改写，cookie/CSRF 不会错乱。
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

    /**
     * 反向代理的目标站（如 `https://hanime1.me`），`start(targetBase)` 时传入。
     * 服务端收到请求 path（如 `/login`）后拼出完整目标 URL。
     */
    @Volatile
    var targetBase: String = ""
        private set

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

    fun baseUrl(): String {
        ensureRunning()
        return "http://127.0.0.1:$port/"
    }

    /**
     * 反向代理模式：path（如 `/login`）→ `http://127.0.0.1:port/login`。
     * 目标站由 [targetBase] 决定，服务端收到后拼出完整 URL 再经 ECH 转发。
     */
    fun proxyUrl(path: String): String {
        ensureRunning()
        val p = if (path.startsWith("/")) path else "/$path"
        return "http://127.0.0.1:$port$p"
    }

    /** 从完整目标 URL 切出 targetBase（scheme+host），如 `https://hanime1.me/login` → `https://hanime1.me` */
    fun targetBaseOf(fullUrl: String): String =
        Regex("^(https?://[^/]+)").find(fullUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("bad target url: $fullUrl")

    /** 从完整目标 URL 切出 path，如 `https://hanime1.me/login` → `/login` */
    fun pathOf(fullUrl: String): String {
        val base = targetBaseOf(fullUrl)
        val p = fullUrl.removePrefix(base).ifEmpty { "/" }
        return if (p.startsWith("/")) p else "/$p"
    }

    /**
     * 反向代理模式入口：记住目标站并返回 WebView 可直接打开的代理 URL。
     * 如 `openTarget("https://hanime1.me/login")` → `http://127.0.0.1:8080/login`。
     * （`HANIME_BASE_URL` 可配镜像站，base 不能写死，从完整 URL 里切。）
     */
    fun openTarget(fullUrl: String): String {
        ensureRunning(targetBaseOf(fullUrl))
        return proxyUrl(pathOf(fullUrl))
    }

    fun isProxyUrl(url: String): Boolean =
        port > 0 && url.startsWith("http://127.0.0.1:$port/")

    @Synchronized
    fun ensureRunning(targetBase: String = this.targetBase): Int {
        if (!isRunning) {
            start(targetBase)
        } else if (targetBase.isNotEmpty() && targetBase != this.targetBase) {
            // socket 不用重启，只换目标站（登录页和 CF 验证页不会同时开）
            this.targetBase = targetBase
            Log.i(TAG, "targetBase switched to $targetBase")
        }
        return port
    }

    @Synchronized
    fun start(targetBase: String = this.targetBase): Int {
        if (isRunning) {
            if (targetBase.isNotEmpty()) this.targetBase = targetBase
            return port
        }
        if (targetBase.isNotEmpty()) this.targetBase = targetBase
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
        // 反向代理模式：rawPath 就是目标站的 path（如 /login?a=b），前面拼 targetBase。
        // targetBase 为空说明还没人调过 openTarget/ensureRunning(base)，直接 400。
        val base = targetBase
        if (base.isEmpty() || !rawPath.startsWith("/")) return null
        val targetUrl = base + rawPath

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
        // Referer/Origin 改写：浏览器发的是代理地址（如 http://127.0.0.1:8080/login），
        // 服务器 CSRF 校验要求 Referer 是本站域名，否则直接 419。改写成目标站地址。
        val proxyPrefix = "http://127.0.0.1:$port"
        for ((k, v) in req.headers) {
            if (isHopHeader(k)) continue
            val ln = k.lowercase()
            if ((ln == "referer" || ln == "origin") && v.startsWith(proxyPrefix)) {
                val rewritten = targetBase + v.removePrefix(proxyPrefix)
                Log.i(TAG, "rewrite $k: $v -> $rewritten")
                builder.addHeader(k, rewritten)
            } else {
                builder.addHeader(k, v)
            }
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
     * 保留以防万一，目前调用处已注释掉。
     *
     * 反向代理模式下 WebView 的所有站内导航本来就在代理域名下（`http://127.0.0.1:port/...`），
     * 不需要改写；登录成功的 302（Location 是真实 https 站点）由各 Activity 自己的
     * `shouldOverrideUrlLoading` 判定处理。如将来需要把 https 导航接回代理，调 `openTarget(url)` 即可。
     */
    fun overrideUrlLoading(
        view: android.webkit.WebView,
        @Suppress("UNUSED_PARAMETER") url: String,
    ): Boolean = false
}
