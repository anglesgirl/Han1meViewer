package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.util.CookieString
import com.yenaly.han1meviewer.util.toLoginCookieList
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
        val cookies = mutableListOf<Cookie>()
        cookieMap[host]?.let { cookies.addAll(it) }

        cookies.addAll(Preferences.loginCookieStateFlow.value.toLoginCookieList(host))
        cookies.addAll(Preferences.cloudFlareCookieStateFlow.value.toLoginCookieList(host))

        Log.d("HCookieJar", "loadForRequest for $host: $cookies")

        return cookies
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // 按 name 合并(同名覆盖、异名保留):302 与最终响应分两次回写时
        // 直接整体替换会丢掉另一跳的 cookie(如 remember_web)。
        val merged = (cookieMap[url.host] ?: mutableListOf()).associateBy { it.name }.toMutableMap()
        cookies.forEach { merged[it.name] = it }
        // 登录态 extras 同名覆盖(不另行追加,防重复堆积)
        Preferences.loginCookieStateFlow.value.toLoginCookieList(url.host).forEach { merged[it.name] = it }
        cookieMap[url.host] = merged.values.toMutableList()
    }
}