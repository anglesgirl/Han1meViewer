package com.yenaly.han1meviewer.logic.network.interceptor

import com.yenaly.han1meviewer.logic.network.HCookieJar
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cronet 模式下的 Cookie 拦截器。
 *
 * CronetInterceptor 会绕过 OkHttp 核心（包括 CookieJar），
 * 因此需要这个应用拦截器手动处理 Cookie 的加载和保存。
 *
 * 必须添加在 CronetInterceptor 之前。
 */
class CronetCookieInterceptor(
    private val cookieJar: HCookieJar,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 加载 Cookie 并添加到请求头
        val cookies = cookieJar.loadForRequest(originalRequest.url)
        val request = if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            originalRequest.newBuilder()
                .header("Cookie", cookieHeader)
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        // 从响应中保存 Cookie
        val setCookieHeaders = response.headers("Set-Cookie")
        if (setCookieHeaders.isNotEmpty()) {
            val parsedCookies = setCookieHeaders.mapNotNull { header ->
                Cookie.parse(response.request.url, header)
            }
            if (parsedCookies.isNotEmpty()) {
                cookieJar.saveFromResponse(response.request.url, parsedCookies)
            }
        }

        return response
    }
}
