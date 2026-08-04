package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * ECH 拦截器:把当前站点(hanime1.me/javchu.com 等)的请求改写为
 * http://127.0.0.1:<echPort>/<path> + X-Ech-Target:<host> header,
 * 让 Go ECH 代理内部用 ECH TLS 连接目标(隐藏 SNI,绕过封锁)。
 *
 * cookie 处理:改写后 OkHttp 的 CookieJar 会按 127.0.0.1 匹配域名,
 * 导致登录态丢失。这里手动注入原始域名的 cookie(从 HCookieJar 读),
 * 并在响应后把 Set-Cookie 存回原始域名。
 *
 * 非站点域名不拦截,走正常网络栈。ECH 关闭或代理未就绪时不拦截。
 */
class EchInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // ECH 关闭或代理未就绪 → 原样放行
        if (!SettingsRepository.useEch) return chain.proceed(request)
        val echPort = EchProxyManager.port
        if (echPort <= 0) return chain.proceed(request)

        // 仅站点域名走 ECH(动态匹配:官方域名列表 + 当前 baseUrl)
        val originHost = url.host
        if (!echDomainMatches(originHost)) return chain.proceed(request)

        // 改写: http://127.0.0.1:port/path?query + X-Ech-Target: host
        val proxyUrl = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(echPort)
            .encodedPath(url.encodedPath)
            .encodedQuery(url.encodedQuery ?: "")
            .build()

        val builder = request.newBuilder()
            .url(proxyUrl)
            .header("X-Ech-Target", originHost)
            .header("Host", originHost)

        // 手动注入原始域名的 cookie(OkHttp CookieJar 按 127.0.0.1 匹配不到)
        val originCookies = HCookieJar.loadCookiesFor(originHost)
        if (originCookies.isNotEmpty()) {
            val cookieHeader = originCookies.joinToString("; ") { "${it.name}=${it.value}" }
            builder.header("Cookie", cookieHeader)
        }

        val proxied = builder.build()
        LogUtil.record("D", "EchProxy", "ECH route ${originHost}${url.encodedPath} -> 127.0.0.1:$echPort")

        val response = chain.proceed(proxied)

        // 响应里的 Set-Cookie 存回原始域名
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val parsed = setCookies.mapNotNull { raw ->
                runCatching { Cookie.parse(proxyUrl, raw) }.getOrNull()
            }
            if (parsed.isNotEmpty()) {
                HCookieJar.saveCookiesFor(originHost, parsed)
            }
        }
        return response
    }

    private fun echDomainMatches(host: String): Boolean {
        val h = host.lowercase()
        // 官方可切换域名
        io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_HOSTNAME.forEach { d ->
            if (h == d || h.endsWith(".$d")) return true
        }
        // 当前选中的站点/镜像站
        runCatching {
            val base = SettingsRepository.baseUrl
            val domain = base.substringAfter("://").substringBefore("/").lowercase()
            if (domain.isNotBlank() && (h == domain || h.endsWith(".$domain"))) return true
        }
        return false
    }
}
