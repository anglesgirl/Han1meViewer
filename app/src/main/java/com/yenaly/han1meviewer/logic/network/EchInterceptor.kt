package com.yenaly.han1meviewer.logic.network

import android.os.SystemClock
import android.util.Log
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * ECH 拦截器:把站外 HTTPS 请求改写为本地 Go ECH 代理请求。
 *
 * 改写形式: http://127.0.0.1:port/path?query + X-Ech-Target: 原host。
 * POST Body 由 OkHttp 完整发出(无 WebView 拦截丢 Body 问题);
 * 302 由 Go 端返回给 OkHttp,Set-Cookie 经 Go cookiejar + 此处回写双保险。
 *
 * Cookie 桥接:OkHttp 的 CookieJar 按改写后的 127.0.0.1 匹配不到原域,
 * 这里手动按原 URL 存取 HCookieJar(复用其 Preferences 合并逻辑)。
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
        val originCookies = HCookieJar().loadForRequest(url)
        if (originCookies.isNotEmpty()) {
            val cookieHeader = originCookies.joinToString("; ") { "${it.name}=${it.value}" }
            builder.header("Cookie", cookieHeader)
        }

        val proxied = builder.build()
        Log.d("EchProxy", "ECH route $originHost${url.encodedPath} -> 127.0.0.1:$echPort")

        val startMs = SystemClock.elapsedRealtime()
        val response = chain.proceed(proxied)
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        Log.d("EchProxy", "route $originHost${url.encodedPath} -> ${elapsedMs}ms ${response.code}")

        // 响应里的 Set-Cookie 按原始域名解析(代理 URL 是 127.0.0.1,
        // 用它解析 Domain=真实域 的 Cookie 会返回 null 整轮丢弃)
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val parsed = setCookies.mapNotNull { raw ->
                runCatching { Cookie.parse(url, raw) }.getOrNull()
            }
            if (parsed.isNotEmpty()) {
                HCookieJar().saveFromResponse(url, parsed)
            }
        }
        return response
    }
}
