package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.util.CookieString
import io.github.daisukikaffuchino.han1meviewer.util.toLoginCookieList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 用於管理 Cookie。
 *
 * #issue-71: 我竟然栽倒在 Cookie 管理上好幾年了！你去看我以前的管理方式，
 * 是完全錯誤的，竟然還能維持應用正常運行，太離譜了！怪不得切換簡體繁體一直不起作用！
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/13 013 15:20
 */
class HCookieJar : CookieJar {

    companion object {
        @JvmStatic
        val cookieMap: MutableMap<String, MutableList<Cookie>> = mutableMapOf()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = LinkedHashMap<String, Cookie>()
        cookieMap[host]?.forEach { cookies[it.name] = it }
        CookieString(SettingsRepository.current.loginCookie).toLoginCookieList(host)
            .forEach { cookies[it.name] = it }
        if (SettingsRepository.cloudFlareCookieHost == host) {
            CookieString(SettingsRepository.current.cloudFlareCookie).toLoginCookieList(host)
                .forEach { cookies[it.name] = it }
        }

        LogUtil.d("HCookieJar", "loadForRequest for $host: ${cookies.keys}")

        return cookies.values.toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val merged = (cookieMap[url.host] ?: emptyList()).associateBy { it.name }.toMutableMap()
        cookies.forEach { merged[it.name] = it }
        CookieString(SettingsRepository.current.loginCookie).toLoginCookieList(url.host)
            .forEach { merged[it.name] = it }
        cookieMap[url.host] = merged.values.toMutableList()
    }
}
