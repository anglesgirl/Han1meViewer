package io.github.daisukikaffuchino.han1meviewer.logic.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream

/**
 * WebView 的 ECH 拦截:把站点的子资源请求(HTML/JS/CSS/图片等)通过
 * OkHttp + EchInterceptor 走本地 ECH 代理(隐藏 SNI),再返回给 WebView。
 *
 * WebView 自身的网络栈走系统代理 CONNECT 隧道,无法隐藏 SNI,封锁站点
 * (如 javchu.com)会被 GFW 重置。这里在 shouldInterceptRequest 拦截,
 * 让站点流量走与 OkHttp 相同的 ECH 路径。
 */
object EchWebViewClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(EchInterceptor())
            .build()
    }

    /**
     * 拦截站点请求走 ECH。非站点域名或 ECH 未开启返回 null(走 WebView 默认)。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!SettingsRepository.useEch) return null
        if (EchProxyManager.port <= 0) return null
        val url = request.url ?: return null
        // 仅站点域名(拦截器内部也判断,这里提前过滤避免额外开销)
        if (!isSiteHost(url.host)) return null

        return try {
            val okRequest = okhttp3.Request.Builder()
                .url(url.toString())
                .method(request.method ?: "GET", null)
                .build()
            val resp = client.newCall(okRequest).execute()

            // 重定向交给 WebView 处理
            if (resp.isRedirect || resp.code >= 400 && resp.code != 403) {
                resp.close()
                return null
            }

            val body = resp.body?.bytes() ?: ByteArray(0)
            val contentType = resp.header("Content-Type") ?: "text/html"
            // 透传部分重要响应头
            val headers = HashMap<String, String>().apply {
                resp.header("Set-Cookie")?.let { put("Set-Cookie", it) }
                resp.header("Cache-Control")?.let { put("Cache-Control", it) }
                resp.header("Content-Type")?.let { put("Content-Type", it) }
            }
            WebResourceResponse(
                contentType,
                charsetFrom(contentType),
                resp.code,
                resp.message,
                headers,
                ByteArrayInputStream(body),
            )
        } catch (e: Exception) {
            LogUtil.record("W", "EchProxy", "WebView ECH intercept failed: ${e.message}")
            null
        }
    }

    private fun isSiteHost(host: String): Boolean {
        val h = host.lowercase()
        io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_HOSTNAME.forEach { d ->
            if (h == d || h.endsWith(".$d")) return true
        }
        runCatching {
            val base = SettingsRepository.baseUrl
            val domain = base.substringAfter("://").substringBefore("/").lowercase()
            if (domain.isNotBlank() && (h == domain || h.endsWith(".$domain"))) return true
        }
        return false
    }

    private fun charsetFrom(contentType: String): String {
        val m = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)
        return m?.groupValues?.get(1) ?: "utf-8"
    }
}
