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
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

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
 *  - HTML 响应里的绝对地址（如 `action="https://hanime1.me/login"`）会被改写为
 *    代理地址，否则 WebView 提交表单时会绕过代理直接发 HTTPS，导致 origin: null
 *    触发服务器 CSRF 419。改写前若响应是 gzip/deflate 会先解压，改完以明文返回。
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

    /**
     * 最后一次 419 的诊断文本（已打码），供登录页"诊断"弹窗本地展示。
     * log 服务器上报不通时，用户截图此文本发回即可定位。
     */
    @Volatile
    var last419Diag: String = ""
        private set

    /** 最近一次 POST /login 的请求摘要（已打码），419 响应到来时拼入 [last419Diag] */
    @Volatile
    private var pendingPostDiag: String = ""

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
        // 419 诊断：POST /login 打印请求头 + body（密码打码）
        val diagPath = req.targetUrl.removePrefix(targetBase)
        if (req.method == "POST" && diagPath.contains("login", ignoreCase = true)) {
            logLoginPostDiag(diagPath, req)
        }
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
                // 419 诊断：GET /login 的 Set-Cookie（值打码，只留名和属性）
                if (req.method == "GET" && diagPath.contains("login", ignoreCase = true)) {
                    val setCookies = it.headers.values("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        logSetCookieDiag(diagPath, setCookies)
                    }
                }
                // 419 诊断：419 响应打印完整响应头
                if (it.code == 419) {
                    log419Diag(req.method, req.targetUrl, it.headers)
                }
                var respBody = it.body?.bytes() ?: ByteArray(0)
                val respContentType = it.header("Content-Type") ?: ""
                // 绝对地址改写：页面/JS/CSS 里的绝对地址（如表单 action="https://hanime1.me/login"、
                // JS 里 fetch("https://hanime1.me/login")）会让 WebView 绕过代理直接发 HTTPS，
                // POST 时 origin: null → 服务器 CSRF 报 419。
                // 把 targetBase 改写为本地代理地址，让所有站内请求都走代理经 ECH 转发。
                // 只对文本类型做，图片/字体等二进制不动。
                var strippedEncoding = false
                val rewriteKind = rewriteKindOf(respContentType)
                if (rewriteKind != null) {
                    val contentEncoding = it.header("Content-Encoding") ?: ""
                    var htmlBytes = respBody
                    var canRewrite = contentEncoding.isEmpty()
                    if (contentEncoding.contains("gzip", ignoreCase = true)) {
                        try {
                            htmlBytes = gunzip(htmlBytes)
                            strippedEncoding = true
                            canRewrite = true
                        } catch (e: Exception) {
                            Log.w(TAG, "gunzip failed, skip rewrite: ${e.message}")
                        }
                    } else if (contentEncoding.contains("deflate", ignoreCase = true)) {
                        try {
                            htmlBytes = inflate(htmlBytes)
                            strippedEncoding = true
                            canRewrite = true
                        } catch (e: Exception) {
                            Log.w(TAG, "inflate failed, skip rewrite: ${e.message}")
                        }
                    }
                    if (canRewrite) {
                        val charset = parseCharset(respContentType) ?: Charsets.UTF_8
                        var html = try {
                            htmlBytes.toString(charset)
                        } catch (_: Exception) {
                            htmlBytes.toString(Charsets.UTF_8)
                        }
                        val base = targetBase
                        if (base.isNotEmpty() && html.contains(base)) {
                            val proxyBase = "http://127.0.0.1:$port"
                            html = html.replace(base, proxyBase)
                            Log.i(TAG, "rewrote absolute urls in $rewriteKind: $base -> $proxyBase")
                        }
                        respBody = html.toByteArray(charset)
                    }
                }
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
                    // 文本改写（html/js/css/json）时若解压过 gzip/deflate，以明文返回，不再透传 content-encoding
                    if (ln == "content-encoding" && strippedEncoding) continue
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
                // 例外：文本改写时已解压（strippedEncoding），此时以明文返回，
                // 上面循环里已跳过 content-encoding 头。
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

    /**
     * 把代理地址头（Referer/Origin）改写为目标站地址，与 forward() 的改写规则一致，
     * 供 419 诊断展示服务器实际收到的值。
     */
    private fun rewriteProxyHeader(value: String): String {
        if (value.isBlank()) return value
        val proxyPrefix = "http://127.0.0.1:$port"
        return if (value.startsWith(proxyPrefix)) targetBase + value.removePrefix(proxyPrefix)
        else value
    }

    // ---------- 419 诊断日志 ----------

    /** 419 诊断：POST /login 的请求头 + body（Cookie 值打码，密码字段打码） */
    private fun logLoginPostDiag(path: String, req: ProxyRequest) {
        val sb = StringBuilder()
        sb.append("== 419 diag: POST ").append(path).append(" ==")
        var cookieNames = ""
        var hasCookie = false
        var referer = ""
        var origin = ""
        var contentType = ""
        for ((k, v) in req.headers) {
            when {
                k.equals("Cookie", ignoreCase = true) -> {
                    hasCookie = v.isNotBlank()
                    cookieNames = cookieNamesOf(v)
                }
                k.equals("Referer", ignoreCase = true) -> referer = v
                k.equals("Origin", ignoreCase = true) -> origin = v
                k.equals("Content-Type", ignoreCase = true) -> contentType = v
            }
            val display = if (k.equals("Cookie", ignoreCase = true) ||
                k.equals("Authorization", ignoreCase = true) ||
                k.equals("Proxy-Authorization", ignoreCase = true)
            ) {
                maskCookieValues(v)
            } else {
                v
            }
            sb.append("\n  ").append(k).append(": ").append(display)
        }
        var hasToken = false
        if (req.body.isNotEmpty()) {
            val bodyStr = try {
                req.body.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                "<binary ${req.body.size} bytes>"
            }
            hasToken = bodyStr.contains("_token", ignoreCase = true)
            sb.append("\n  body(500): ").append(maskPasswordFields(bodyStr.take(500)))
        } else {
            sb.append("\n  body: <empty>")
        }
        // 本地 419 诊断弹窗用：拼一份打码摘要，419 响应到来时拼入 last419Diag
        val cookieCount = if (cookieNames.isBlank()) 0 else cookieNames.split(",").size
        pendingPostDiag = buildString {
            append("Cookie: ")
            if (cookieNames.isBlank()) append("<无>") else append(cookieNames)
            append(" (").append(cookieCount).append("个)")
            append("\nHas _token: ").append(hasToken)
            append("\nReferer: ").append(rewriteProxyHeader(referer).ifBlank { "<无>" })
            append("\nOrigin: ").append(rewriteProxyHeader(origin).ifBlank { "<无>" })
        }
        Log.d(TAG, sb.toString())
        // 上报到 log 服务器（用户抓不了包，远程看）
        EchLogReporter.report(
            "mini_login_post",
            mapOf(
                "path" to path,
                "has_cookie" to hasCookie,
                "cookie_names" to cookieNames,
                "has_token" to hasToken,
                "referer" to referer,
                "origin" to origin,
                "content_type" to contentType,
            ),
        )
    }

    /** 419 诊断：GET /login 的 Set-Cookie（值打码，只留 cookie 名和属性） */
    private fun logSetCookieDiag(path: String, setCookies: List<String>) {
        val sb = StringBuilder()
        sb.append("== 419 diag: GET ").append(path).append(" Set-Cookie ==")
        val names = ArrayList<String>(setCookies.size)
        for (sc in setCookies) {
            sb.append("\n  ").append(maskSetCookieValue(sc))
            val eq = sc.indexOf('=')
            if (eq > 0) names.add(sc.substring(0, eq).trim())
        }
        Log.d(TAG, sb.toString())
        EchLogReporter.report(
            "mini_login_get_cookie",
            mapOf(
                "path" to path,
                "cookie_names" to names.joinToString(","),
                "count" to setCookies.size,
            ),
        )
    }

    /** 419 诊断：419 响应的完整响应头（Set-Cookie 值打码） */
    private fun log419Diag(method: String, targetUrl: String, headers: okhttp3.Headers) {
        val sb = StringBuilder()
        sb.append("== 419 diag: 419 response headers for ").append(targetUrl).append(" ==")
        for ((name, value) in headers) {
            val display = if (name.equals("Set-Cookie", ignoreCase = true)) {
                maskSetCookieValue(value)
            } else {
                value
            }
            sb.append("\n  ").append(name).append(": ").append(display)
        }
        // 本地 419 诊断弹窗：拼请求摘要（已打码）+ 419 标记，供用户截图发回
        val path = targetUrl.removePrefix(targetBase)
        val postDiag = pendingPostDiag
        last419Diag = buildString {
            append(method).append(' ').append(path.ifEmpty { "/" }).append(" → 419")
            if (postDiag.isNotEmpty() &&
                method.equals("POST", ignoreCase = true) &&
                path.contains("login", ignoreCase = true)
            ) {
                append('\n').append(postDiag)
                pendingPostDiag = "" // 消费一次，避免陈旧数据污染下一次
            }
        }
        Log.d(TAG, sb.toString())
        EchLogReporter.report(
            "mini_login_419",
            mapOf(
                "path" to path,
                "method" to method,
            ),
        )
    }

    /** 取 Cookie 请求头的名列表（值不取）：`a=1; b=2` → `a,b` */
    private fun cookieNamesOf(cookieHeader: String): String =
        cookieHeader.split(";").mapNotNull { part ->
            val t = part.trim()
            val eq = t.indexOf('=')
            if (eq > 0) t.substring(0, eq).trim().takeIf { it.isNotEmpty() } else null
        }.joinToString(",")

    /** 打码 Cookie 请求头：保留名，值→*** */
    private fun maskCookieValues(cookieHeader: String): String =
        cookieHeader.split(";").joinToString("; ") { part ->
            val t = part.trim()
            val eq = t.indexOf('=')
            if (eq > 0) t.substring(0, eq + 1) + "***" else t
        }

    /** 打码 Set-Cookie：只留 cookie 名和属性，值→*** */
    private fun maskSetCookieValue(setCookie: String): String =
        setCookie.replace(Regex("^([^=;\\s]+)=[^;]*"), "$1=***")

    /** 打码 form body 里的密码类字段值（_token 等 CSRF 字段保留可见） */
    private fun maskPasswordFields(body: String): String =
        body.replace(Regex("(?i)((?:password|passwd|pwd)[^&=]*=)([^&\\s]*)"), "$1***")

    // ---------- 绝对地址改写辅助 ----------

    /**
     * 需要改写绝对地址的文本响应类型。返回日志用名；null 表示不改写。
     * 覆盖 html / js / css / json 等文本类型，二进制（图片/字体/视频）不动。
     */
    private fun rewriteKindOf(contentType: String): String? {
        val ct = contentType.lowercase()
        return when {
            ct.contains("text/html") -> "html"
            ct.contains("text/css") -> "css"
            ct.contains("application/json") || ct.contains("text/json") -> "json"
            ct.contains("javascript") || ct.contains("ecmascript") -> "js"
            else -> null
        }
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private fun inflate(data: ByteArray): ByteArray =
        InflaterInputStream(data.inputStream()).use { it.readBytes() }

    /** 从 Content-Type 头解析 charset，如 `text/html; charset=utf-8` */
    private fun parseCharset(contentType: String): Charset? {
        val m = Regex("(?i)charset=([^;\\s]+)").find(contentType) ?: return null
        return try {
            Charset.forName(m.groupValues[1].trim().trim('"'))
        } catch (_: Exception) {
            null
        }
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
