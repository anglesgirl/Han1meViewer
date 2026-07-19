package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import okhttp3.Interceptor
import okhttp3.Response

object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().addHeader(
            "User-Agent", USER_AGENT
        ).build()
        return chain.proceed(request)
    }
}