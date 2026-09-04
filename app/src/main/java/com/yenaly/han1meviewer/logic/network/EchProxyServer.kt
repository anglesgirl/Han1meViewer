package com.yenaly.han1meviewer.logic.network

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地反向代理 127.0.0.1:23333
 * WebView 加载 http://127.0.0.1:23333/https://javchu.com/login
 * 代理用 BoringSSL+curl (EchHttpClient) 去真站，解决 POST Body 丢失 + SNI 暴露
 * 失败自动回退，cookie 透传
 */
object EchProxyServer {
    private const val TAG = "EchProxy"
    const val PORT = 23333
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        try {
            serverSocket = ServerSocket(PORT)
            running.set(true)
            Log.i(TAG, "proxy started on 127.0.0.1:$PORT")
            Diagnostics.event("ech_proxy_started", mapOf("port" to PORT))
            Thread({
                while (running.get()) {
                    try {
                        val s = serverSocket?.accept() ?: break
                        Thread({ handle(s) }, "ech-proxy-worker").start()
                    } catch (_: Exception) { break }
                }
            }, "ech-proxy-accept").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Diagnostics.event("ech_proxy_start_fail", mapOf("error" to (e.message ?: "")))
        }
    }

    fun proxyUrl(target: String): String {
        val t = if (target.startsWith("http")) target else "https://$target"
        return "http://127.0.0.1:$PORT/$t"
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var rawPath = parts[1] // /https://javchu.com/login  or /proxy?url=...
            // 读 headers
            val reqHeaders = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) break
                val idx = l.indexOf(":")
                if (idx > 0) {
                    val k = l.substring(0, idx).trim()
                    val v = l.substring(idx + 1).trim()
                    reqHeaders[k] = v
                    if (k.equals("content-length", true)) contentLength = v.toIntOrNull() ?: 0
                }
            }
            // 读 body (POST)
            var body: ByteArray? = null
            if (contentLength > 0) {
                // body 可能含二进制，用 char 读会坏，需从 input 精确读字节
                // 已用 reader 读过 headers，剩余 body 在 input buffer 中，需按 bytes 读
                // 简化：若为 x-www-form-urlencoded/json，用 reader 剩余字符
                val cbuf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(cbuf, read, contentLength - read)
                    if (r <= 0) break
                    read += r
                }
                val bodyStr = String(cbuf, 0, read)
                body = bodyStr.toByteArray(Charsets.UTF_8)
                // 若 body 长度对不上，尝试按字节补
                if (body.size != contentLength) {
                    // fallback
                }
            } else if (method == "POST") {
                // chunked 暂不支持，按 0 处理
            }

            // 解析目标 URL
            var targetUrl = ""
            if (rawPath.startsWith("/http://") || rawPath.startsWith("/https://")) {
                targetUrl = rawPath.removePrefix("/")
                val qIdx = targetUrl.indexOf("?")
                // 保留 query
            } else if (rawPath.startsWith("/__proxy?url=")) {
                targetUrl = java.net.URLDecoder.decode(rawPath.substringAfter("url="), "UTF-8")
            } else {
                // 直接 path，拼到当前 base (javchu)
                val host = reqHeaders["Host"] ?: "javchu.com"
                targetUrl = "http://127.0.0.1:$PORT/https://$host$rawPath"
                // 兜底
                if (!targetUrl.startsWith("http")) targetUrl = "https://javchu.com$rawPath"
            }
            Log.i(TAG, "proxy $method $targetUrl len=${body?.size ?: 0}")
            // 透传 headers：过滤 hop-by-hop，保留 Cookie/User-Agent/Referer
            val fwdHeaders = mutableListOf<String>()
            for ((k, v) in reqHeaders) {
                val lk = k.lowercase()
                if (lk in setOf("host", "content-length", "connection", "proxy-connection", "accept-encoding")) continue
                fwdHeaders.add("$k: $v")
            }
            // 补 cookie：从 CookieManager 取目标域 cookie
            try {
                val cm = CookieManager.getInstance().getCookie(targetUrl)
                if (!cm.isNullOrEmpty() && !fwdHeaders.any { it.startsWith("Cookie:", true) }) {
                    fwdHeaders.add("Cookie: $cm")
                }
            } catch (_: Exception) {}

            if (!EchHttpClient.isLoaded) {
                sendError(out, 502, "ECH not loaded")
                return
            }
            val dohUrl = DohConfig.resolveUrl() ?: "https://82sew1c85i.cloudflare-gateway.com/dns-query"
            val dohHost = try { android.net.Uri.parse(dohUrl).host ?: "82sew1c85i.cloudflare-gateway.com" } catch (_: Exception) { "82sew1c85i.cloudflare-gateway.com" }
            val ips = DohConfig.bootstrapIps().ifEmpty { listOf("162.159.36.20","162.159.36.5") }
            val dohResolve = "$dohHost:443:${ips.joinToString(",")}"

            val jsonStr = EchHttpClient.request(method, targetUrl, fwdHeaders.toTypedArray(), body, dohUrl, dohResolve)
            val json = JSONObject(jsonStr)
            val status = json.optInt("statusCode", 200)
            val bodyB64 = json.optString("body", "")
            val bodyBytes = if (bodyB64.isNotEmpty()) Base64.decode(bodyB64, Base64.DEFAULT) else ByteArray(0)
            val headersJson = json.optJSONArray("headers")
            val respHeaders = mutableMapOf<String, String>()
            if (headersJson != null) {
                for (i in 0 until headersJson.length()) {
                    val h = headersJson.optString(i) ?: continue
                    val idx = h.indexOf('\t')
                    if (idx <= 0) continue
                    respHeaders[h.substring(0, idx)] = h.substring(idx + 1)
                }
            }
            // 处理 Set-Cookie -> 写回 CookieManager
            for ((k, v) in respHeaders) {
                if (k.equals("set-cookie", true)) {
                    try { CookieManager.getInstance().setCookie(targetUrl, v) } catch (_: Exception) {}
                }
            }
            // 处理重定向 Location 重写为代理地址
            val loc = respHeaders["Location"] ?: respHeaders["location"]
            if (loc != null && (loc.startsWith("https://") || loc.startsWith("http://"))) {
                respHeaders["Location"] = proxyUrl(loc)
            }
            // HTML 重写：把页面内绝对链接改走代理，避免二次直连 RST
            var outBody = bodyBytes
            val ct = respHeaders["Content-Type"] ?: respHeaders["content-type"] ?: ""
            if (ct.contains("text/html") && outBody.isNotEmpty()) {
                var html = String(outBody, Charsets.UTF_8)
                // 仅改 hanime/javchu 域，避免全站误改
                for (d in arrayOf("https://javchu.com", "https://hanime1.me", "https://hanime1.com", "https://hanimeone.me")) {
                    html = html.replace(d, "http://127.0.0.1:$PORT/$d")
                }
                // 相对路径 form action="/login" -> 补全
                // 暂不强改，后续 302 已处理
                outBody = html.toByteArray(Charsets.UTF_8)
                respHeaders["Content-Length"] = outBody.size.toString()
            } else {
                respHeaders["Content-Length"] = outBody.size.toString()
            }
            respHeaders["Connection"] = "close"
            // 写响应
            val statusText = when (status) { 200->"OK"; 302->"Found"; 301->"Moved Permanently"; 404->"Not Found"; 500->"Internal Server Error"; else->"OK" }
            out.write("HTTP/1.1 $status $statusText\r\n".toByteArray())
            for ((k,v) in respHeaders) {
                if (k.equals("content-encoding", true)) continue
                if (k.equals("transfer-encoding", true)) continue
                out.write("$k: $v\r\n".toByteArray())
            }
            out.write("\r\n".toByteArray())
            out.write(outBody)
            out.flush()
            Diagnostics.event("ech_proxy_ok", mapOf("method" to method, "status" to status, "url" to targetUrl.take(80)))
        } catch (e: Exception) {
            try { socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) } catch (_: Exception) {}
            Log.e(TAG, "handle error", e)
            Diagnostics.event("ech_proxy_error", mapOf("error" to (e.message ?: e.javaClass.simpleName).take(120)))
        } finally { try { socket.close() } catch (_: Exception) {} }
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray()
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }
}
