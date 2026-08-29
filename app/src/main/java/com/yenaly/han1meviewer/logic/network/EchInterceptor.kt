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
            PostHogManager.track("ech_fail", mapOf("host" to host))
            Diagnostics.event("ech_intercept_failure", mapOf(
                "host" to host,
                "error_type" to e.javaClass.simpleName,
                "error" to (e.message ?: "unknown")
            ))
            // fail-closed: HANIME 域名不回落明文 SNI
            if (host in HanimeConstants.HANIME_HOSTNAME || host.endsWith("hanime1.me") || host.endsWith("javchu.com")) {
                throw IOException("ECH request failed for $host: ${e.message}", e)
            }
            return chain.proceed(request)
        }
    }
}
