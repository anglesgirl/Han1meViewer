package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import android.os.SystemClock

/**
 * ECH 拦截器:把**所有** HTTPS 请求改写为
 * http://127.0.0.1:<echPort>/<path> + X-Ech-Target:<host> header,
 * 让 Go ECH 代理统一处理:
 *   - 目标支持 ECH → ECH 握手(隐藏 SNI,绕过封锁,如 javchu.com)
 *   - 目标不支持 ECH → 普通 TLS(DoH 解析 + 直连,如 COS/GitHub API)
 * 所有流量交给 ECH 代理,不需要用户配置代理或开关。
 *
 * cookie 处理:改写后 OkHttp 的 CookieJar 会按 127.0.0.1 匹配域名,
 * 导致登录态丢失。这里手动注入原始域名的 cookie(从 HCookieJar 读),
 * 并在响应后把 Set-Cookie 存回原始域名。
 *
 * 代理未启动(port<=0)时放行直连,避免全断。
 */
class EchInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 代理未启动 → 直连(兜底,不会全断)
        val echPort = EchProxyManager.port
        if (echPort <= 0) return chain.proceed(request)

        val originHost = url.host
        // 本地/内网/空 host 不走代理
        if (originHost.isBlank() ||
            originHost == "127.0.0.1" || originHost == "localhost" ||
            originHost.endsWith(".local")
        ) {
            return chain.proceed(request)
        }

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

        val startMs = SystemClock.elapsedRealtime()
        val response = chain.proceed(proxied)
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        // 耗时日志:诊断慢请求/丢帧时直接看每请求耗时,不用猜。
        LogUtil.record("D", "EchProxy", "route ${originHost}${url.encodedPath} -> ${elapsedMs}ms ${response.code}")

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
}
