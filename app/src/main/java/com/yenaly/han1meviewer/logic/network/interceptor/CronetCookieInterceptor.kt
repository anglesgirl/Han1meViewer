package com.yenaly.han1meviewer.logic.network.interceptor

import com.yenaly.han1meviewer.logic.network.HCookieJar
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cronet 模式下 OkHttp CookieJar 被绕过，
 * 此拦截器手动从请求头提取 Cookie 存入 HCookieJar，
 * 并从响应头 Set-Cookie 读取保存。
 */
class CronetCookieInterceptor(
    private val cookieJar: HCookieJar,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 从 CookieJar 获取 cookie 并添加到请求头
        val cookies = cookieJar.loadForRequest(url)
        val requestWithCookies = if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            request.newBuilder().header("Cookie", cookieHeader).build()
        } else {
            request
        }

        val response = chain.proceed(requestWithCookies)

        // 从响应头提取 Set-Cookie 并保存
        response.headers("Set-Cookie").forEach { setCookie ->
            val parsed = okhttp3.Cookie.parse(url, setCookie)
            if (parsed != null) {
                cookieJar.saveFromResponse(url, listOf(parsed))
            }
        }

        return response
    }
}
