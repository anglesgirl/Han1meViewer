package com.yenaly.han1meviewer.logic.network

import android.os.SystemClock
import android.util.Log
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * ECH 拦截器:把站外 HTTPS 请求改写为本地 Go ECH 代理请求。
 *
 * 必须注册为普通应用拦截器:网络拦截器不允许改 host,注册错会全网崩溃。
 * 三个客户端统一用 NO_COOKIES,OkHttp 核心不碰 Cookie,此处按原始域名
 * 手动注入(HCookieJar 同源)/回写,不存在按 127.0.0.1 覆盖问题。
 *
 * 改写形式: http://127.0.0.1:port/path?query + X-Ech-Target: 原host。
 * POST Body 由 OkHttp 完整发出(无 WebView 拦截丢 Body 问题);
 * 302 由 Go 端以内嵌形态返回给 OkHttp,Set-Cookie 按原始域名回写。
 *
 * Cookie 桥接:内存 cookieMap 按原始域名存取,与 HCookieJar 共享。
 */
class EchInterceptor : Interceptor {

    companion object {
        // api.github 裸连可用,更新检查直连不走代理(更快,已验证)。
        private val DIRECT_HOSTS = setOf("api.github.com")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 代理未启动 → 直连(兜底,不会全断)
        val echPort = EchProxyManager.port
        if (echPort <= 0) return chain.proceed(request)

        val originHost = url.host
        // 本地/内网/空 host 不走代理;更新源直连
        if (originHost.isBlank() ||
            originHost == "127.0.0.1" || originHost == "localhost" ||
            originHost.endsWith(".local") || originHost in DIRECT_HOSTS
        ) {
            return chain.proceed(request)
        }

        // 改写: http://127.0.0.1:port/path?query + X-Ech-Target: host
        val proxyBuilder = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(echPort)
            .encodedPath(url.encodedPath)
        url.encodedQuery?.let { proxyBuilder.encodedQuery(it) }
        val proxyUrl = proxyBuilder.build()

        val builder = request.newBuilder()
            .url(proxyUrl)
            .header("X-Ech-Target", originHost)
            .header("Host", originHost)
        // 手动注入原始域名的 cookie(客户端全是 NO_COOKIES,核心不碰)。
        // 按 name 去重:内存最新响应覆盖持久化旧会话,同名多份必 419。
        val originCookies = HCookieJar().loadForRequest(url)
        if (originCookies.isNotEmpty()) {
            val deduped = LinkedHashMap<String, String>()
            originCookies.forEach { deduped[it.name] = it.value }
            builder.header(
                "Cookie",
                deduped.entries.joinToString("; ") { "${it.key}=${it.value}" }
            )
        }
        val proxied = builder.build()
        if (BuildConfig.DEBUG) {
            Log.d("EchProxy", "ECH route $originHost${url.encodedPath} -> 127.0.0.1:$echPort")
        }

        val startMs = SystemClock.elapsedRealtime()
        val response = chain.proceed(proxied)
        if (BuildConfig.DEBUG) {
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            Log.d("EchProxy", "route $originHost${url.encodedPath} -> ${elapsedMs}ms ${response.code}")
        }

        // 响应里的 Set-Cookie 按原始域名存回(必须用原始 url 解析,
        // 用 127.0.0.1 解析会因 Domain 不匹配被丢弃,导致登录态丢)。
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
