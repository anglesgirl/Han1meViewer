package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Cookie
import okhttp3.Response

/** 将目标 HTTPS 请求改写为本地 HTTP，由 Go 代理完成 DoH、ECH 和 TLS。 */
class EchInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        if (url.scheme != "https" || isLocal(url.host)) return chain.proceed(original)

        val port = EchProxyManager.port
        check(port > 0) { "ECH 代理未就绪，拒绝目标站点明文直连: ${url.host}" }

        val proxyUrl = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(port)
            .encodedPath(url.encodedPath)
            .apply { url.encodedQuery?.let(::encodedQuery) }
            .build()

        val response = chain.proceed(
            original.newBuilder()
                .url(proxyUrl)
                .header("X-Ech-Target", url.host)
                .header("Host", url.host)
                .build()
        )
        val cookies = response.headers("Set-Cookie").mapNotNull { raw ->
            Cookie.parse(url, raw)
        }
        if (cookies.isNotEmpty()) HCookieJar().saveFromResponse(url, cookies)
        return response
    }

    private fun isLocal(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host.endsWith(".local")
}