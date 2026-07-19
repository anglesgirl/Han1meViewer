package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.DESKTOP_USER_AGENT
import okhttp3.Interceptor
import okhttp3.Response

class GetchuInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", "https://www.getchu.com/")
            .header("Cookie", "getchu_adalt_flag=getchu.com; gc=gc")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .build()
        return chain.proceed(request)
    }
}
