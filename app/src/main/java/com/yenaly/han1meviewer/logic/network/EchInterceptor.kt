package com.yenaly.han1meviewer.logic.network

import android.util.Base64
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.HanimeConstants
import com.yenaly.han1meviewer.analytics.PostHogManager
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException

class EchInterceptor : Interceptor {

    private val hanimeHosts = HanimeConstants.HANIME_HOSTNAME.toSet() + setOf("www.getchu.com", "store.ubisoft.com")

    private fun shouldIntercept(host: String): Boolean {
        if (!EchHttpClient.isLoaded) return false
        return host in hanimeHosts || host.endsWith("hanime1.me") || host.endsWith("hanime1.com") || host.endsWith("hanimeone.me") || host.endsWith("javchu.com")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!shouldIntercept(host)) return chain.proceed(request)

        val dohUrl = DohConfig.resolveUrl() ?: "https://82sew1c85i.cloudflare-gateway.com/dns-query"
        val dohHost = try { dohUrl.toHttpUrl().host } catch (_: Exception) { "82sew1c85i.cloudflare-gateway.com" }
        val ips = DohConfig.bootstrapIps().ifEmpty { listOf("162.159.36.20","162.159.36.5") }
        val dohResolve = "$dohHost:443:${ips.joinToString(",")}"

        // 收集请求头 + 注入 Cookie
        val headers = mutableListOf<String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            val value = request.headers.value(i)
            if (name.equals("Host", true) || name.equals("Content-Length", true)) continue
            headers.add("$name: $value")
        }
        // 手动注入 Cookie（因为我们短路了 OkHttp 的 CookieJar）
        if (request.header("Cookie") == null) {
            val cookies = HCookieJar().loadForRequest(request.url)
            if (cookies.isNotEmpty()) {
                val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                headers.add("Cookie: $cookieHeader")
            }
        }

        val bodyBytes: ByteArray? = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        }

        val method = request.method

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val jsonStr = EchHttpClient.request(method, request.url.toString(), headers.toTypedArray(), bodyBytes, dohUrl, dohResolve)
            val json = JSONObject(jsonStr)
            val statusCode = json.optInt("statusCode", 200)
            val bodyBase64 = json.optString("body", "")
            val echStatus = json.optString("echStatus", "")
            val headersJson = json.optJSONArray("headers")
            val echLogs = json.optJSONArray("echLogs")

            val bodyBytesDecoded = if (bodyBase64.isNotEmpty()) Base64.decode(bodyBase64, Base64.DEFAULT) else ByteArray(0)
            val contentType = headersJson?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i) ?: continue
                    val idx = h.indexOf('\t')
                    if (idx > 0 && h.substring(0, idx).equals("content-type", true)) return@let h.substring(idx+1)
                }
                null
            }?.toMediaTypeOrNull()

            val responseBody = bodyBytesDecoded.toResponseBody(contentType)

            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message(echStatus.ifEmpty { "OK" })
                .body(responseBody)

            // 添加响应头并处理 Set-Cookie
            val responseHeaders = Headers.Builder()
            val cookiesToSave = mutableListOf<Cookie>()
            if (headersJson != null) {
                for (i in 0 until headersJson.length()) {
                    val h = headersJson.optString(i) ?: continue
                    val idx = h.indexOf('\t')
                    if (idx <= 0) continue
                    val name = h.substring(0, idx)
                    val value = h.substring(idx+1)
                    responseHeaders.add(name, value)
                    if (name.equals("set-cookie", true)) {
                        Cookie.parse(request.url, value)?.let { cookiesToSave.add(it) }
                    }
                }
            }
            if (cookiesToSave.isNotEmpty()) {
                HCookieJar().saveFromResponse(request.url, cookiesToSave)
            }
            builder.headers(responseHeaders.build())

            Diagnostics.event("ech_intercept", mapOf(
                "host" to host,
                "status" to statusCode,
                "ech_status" to echStatus,
                "method" to method
            ))
            PostHogManager.track("ech_success", mapOf("host" to host))
            if (echLogs != null && echLogs.length() > 0) {
                // 只取最后一条日志避免刷屏
                Diagnostics.event("ech_logs", mapOf("host" to host, "log" to echLogs.optString(echLogs.length()-1)))
            }
            return builder.build()
            } catch (e: Exception) {
                lastError = e
                val isEch = e.message?.contains("ECH", true) == true
                PostHogManager.track("ech_fail", mapOf("host" to host, "attempt" to (attempt+1), "is_ech" to isEch))
                Diagnostics.event("ech_intercept_failure", mapOf(
                    "host" to host,
                    "attempt" to (attempt+1),
                    "error_type" to e.javaClass.simpleName,
                    "error" to (e.message ?: "unknown")
                ))
                if (!isEch || attempt == 1) {
                    // fail-closed：目标请求失败时不回落系统网络，避免明文 SNI。
                    throw lastError ?: IOException("ECH request failed")
                }
                // ECH 失败（公钥轮换/过期）：
                // 1) 用同一 DoH 查 cloudflare-ech.com 强制刷新全局 ECH 缓存（绕 .so 30 分钟缓存）
                // 2) 通知 ech-sync Worker 立即更新 x.xn--pn1aul.eu.org 的 HTTPS 记录
                // 3) 稍等后重试原请求
                try {
                    val warmUrl = dohUrl + (if (dohUrl.contains("?")) "&" else "?") + "name=cloudflare-ech.com&type=65&_=" + System.currentTimeMillis()
                    val warmReq = okhttp3.Request.Builder().url(warmUrl).addHeader("Accept", "application/dns-json").build()
                    val warmCli = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    warmCli.newCall(warmReq).execute().use { resp -> resp.body?.string() }
                    Diagnostics.event("ech_warm_cf", mapOf("host" to host))
                } catch (_: Exception) {}
                // 通知 ech-sync Worker 立即同步（App 专用 key；触发失败不影响主流程）
                try {
                    val notifyReq = okhttp3.Request.Builder()
                        .url("https://ech-sync.lintoya.workers.dev/?key=a1b6071f9147b44e0b1e08b25aee9ee3")
                        .get().build()
                    val notifyCli = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    notifyCli.newCall(notifyReq).execute().use { resp -> resp.body?.string() }
                    Diagnostics.event("ech_sync_notify", mapOf("host" to host, "status" to "ok"))
                } catch (e: Exception) {
                    Diagnostics.event("ech_sync_notify_fail", mapOf("host" to host, "err" to (e.message ?: "unknown")))
                }
                // 等待 DoH TTL / Worker 更新传播后重试一次
                try { Thread.sleep(300) } catch (_: Exception) {}
            }
        }
        // 理论上不会到这里
        return chain.proceed(request)
    }
}
