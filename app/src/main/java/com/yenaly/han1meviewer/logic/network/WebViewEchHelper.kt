package com.yenaly.han1meviewer.logic.network

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.HanimeConstants
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import org.json.JSONObject
import java.io.ByteArrayInputStream

object WebViewEchHelper {
    private val hanimeHosts = HanimeConstants.HANIME_HOSTNAME.toSet()

    private fun shouldIntercept(host: String?): Boolean = true

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host ?: return null
        if (!shouldIntercept(host)) return null
        return try {
            val url = request.url.toString()
            val method = request.method ?: "GET"
            // WebView 的 requestHeaders 已包含 Cookie / User-Agent
            val headers = request.requestHeaders.map { (k, v) -> "$k: $v" }.toTypedArray()
            val dohUrl = DohConfig.resolveUrl() ?: "https://82sew1c85i.cloudflare-gateway.com/dns-query"
            val dohHost = try { android.net.Uri.parse(dohUrl).host ?: "82sew1c85i.cloudflare-gateway.com" } catch (_: Exception) { "82sew1c85i.cloudflare-gateway.com" }
            val ips = DohConfig.bootstrapIps().ifEmpty { listOf("162.159.36.20","162.159.36.5") }
            val dohResolve = "$dohHost:443:${ips.joinToString(",")}"
            val jsonStr = EchHttpClient.request(method, url, headers, null, dohUrl, dohResolve)
            val json = JSONObject(jsonStr)
            val statusCode = json.optInt("statusCode", 200)
            val bodyBase64 = json.optString("body", "")
            val headersJson = json.optJSONArray("headers")
            val bodyBytes = if (bodyBase64.isNotEmpty()) Base64.decode(bodyBase64, Base64.DEFAULT) else ByteArray(0)

            var mimeType = "text/html"
            var encoding = "utf-8"
            val responseHeaders = mutableMapOf<String, String>()
            if (headersJson != null) {
                for (i in 0 until headersJson.length()) {
                    val h = headersJson.optString(i) ?: continue
                    val idx = h.indexOf('\t')
                    if (idx <= 0) continue
                    val name = h.substring(0, idx)
                    val value = h.substring(idx + 1)
                    responseHeaders[name] = value
                    if (name.equals("content-type", true)) {
                        // e.g. text/html; charset=utf-8
                        val parts = value.split(";")
                        mimeType = parts[0].trim()
                        parts.forEach { p ->
                            if (p.trim().startsWith("charset=", true)) encoding = p.trim().substringAfter("=")
                        }
                    }
                }
            }
            val stream = ByteArrayInputStream(bodyBytes)
            // API 21+ 6参构造支持状态码和头
            WebResourceResponse(mimeType, encoding, statusCode, "OK", responseHeaders, stream)
        } catch (e: Exception) {
            Diagnostics.event("webview_ech_failure", mapOf(
                "host" to host,
                "error_type" to e.javaClass.simpleName,
                "error" to (e.message ?: "unknown"),
            ))
            // 目标域名失败时返回错误响应，禁止 WebView 回落自己的明文网络栈。
            WebResourceResponse(
                "text/plain",
                "utf-8",
                599,
                "ECH request failed",
                emptyMap(),
                ByteArrayInputStream("ECH request failed".toByteArray()),
            )
        }
    }
}
