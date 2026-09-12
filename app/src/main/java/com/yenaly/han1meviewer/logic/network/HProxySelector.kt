package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.ech.EchHosts
import okhttp3.internal.proxy.NullProxySelector
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 受 [EhViewer_CN_SXJ 中 EhProxySelector](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/blob/BiLi_PC_Gamer/app/src/main/java/com/hippo/ehviewer/EhProxySelector.java)
 * 的启发，Han1meViewer 也将使用 [HProxySelector] 来实现代理功能。
 *
 * 注意:必须使用 [HProxySelector.getInstance] 获取单例,不要 new。
 * 这个类的 [alternative] 会在首次使用时懒加载系统默认 ProxySelector;
 * 如果每次 build OkHttpClient 都 new 一个,而全局默认已被 setDefault 成
 * 本类实例,alternative 会捕获到自身,select() 无限递归 → 网络全断 + 卡顿。
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/10/07 007 17:32
 */
// #issue-15: 添加系统代理功能
class HProxySelector : ProxySelector() {

    private var delegation: ProxySelector? = null
    private val alternative: ProxySelector = getDefault() ?: NullProxySelector

    init {
        updateProxy()
    }

    companion object {
        const val TYPE_DIRECT = 0
        const val TYPE_SYSTEM = 1
        const val TYPE_HTTP = 2
        const val TYPE_SOCKS = 3

        private val ipv4Regex =
            Regex("^(([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])\\\\.){3}([01]?\\\\d\\\\d?|2[0-4]\\\\d|25[0-5])$")

        fun validateIp(ip: String): Boolean {
            return ipv4Regex.matches(ip)
        }

        fun validatePort(port: Int): Boolean {
            return port in 0..65535
        }

        // #issue-39: 代理沒有應用到 WebView 上，只能通過此種方式來全局代理。
        // ECH 开启且本地代理就绪时,系统代理统一指向本地 ECH 代理,
        // 让 HttpURLConnection(ExoPlayer)/WebView 也走 ECH;Go 代理对
        // 非 Cloudflare 域名(m3u8/ts CDN)自动降级普通 TLS。
        fun rebuildNetwork() {
            val properties = System.getProperties()
            // ⚠️ 这里原先有一段「ECH 本地代理就绪就把系统代理指向 127.0.0.1:echPort」
            // —— 换 Conscrypt 后已删除：本地代理不存在了，留着会把
            // HttpURLConnection/ExoPlayer 指向死端口。ECH 现在由 OkHttp 传输层承担，
            // WebView/ExoPlayer 另行接线（见 logic/network/ech/）。

            when (Preferences.proxyType) {
                TYPE_HTTP, TYPE_SOCKS -> {
                    properties["proxySet"] = true.toString()
                    properties["proxyHost"] = Preferences.proxyIp
                    properties["proxyPort"] = Preferences.proxyPort.toString()
                }

                else -> {
                    properties["proxySet"] = false.toString()
                    properties["proxyHost"] = ""
                    properties["proxyPort"] = ""
                }
            }
        }
    }

    private fun updateProxy() {
        // 强制解析系统默认(在 setDefault 之前首次访问时捕获真正系统代理)。
        delegation = when (Preferences.proxyType) {
            TYPE_DIRECT -> NullProxySelector
            TYPE_SYSTEM -> alternative
            TYPE_HTTP, TYPE_SOCKS -> null
            else -> NullProxySelector
        }
    }

    override fun select(uri: URI?): MutableList<Proxy> {
        // 受保护域名（ECH 名单）**直连**。三条理由，缺一不可：
        //   ① 直连不会明文外泄 SNI —— 这是唯一必须守住的底线，也是删掉 Go 反代后
        //      由 Conscrypt 在传输层保证的（见 logic/network/ech/）
        //   ② 直连就是最快路径：不经代理、没有 CONNECT 往返
        //   ③ 反过来走代理反而更差：HTTP 代理会把目标域名原样写进 CONNECT 请求行，
        //      等于把封锁域名换个地方暴露
        // 其余域名照常尊重用户在设置里选的代理。
        val host = uri?.host
        if (host != null && EchHosts.isProtected(host)) {
            return mutableListOf(Proxy.NO_PROXY)
        }

        val type = Preferences.proxyType
        if (type == TYPE_HTTP || type == TYPE_SOCKS) {
            val ip = Preferences.proxyIp
            val port = Preferences.proxyPort
            if (ip.isNotBlank() && port != -1) {
                return try {
                    val inetAddress = InetAddress.getByName(ip)
                    val socketAddress = InetSocketAddress(inetAddress, port)
                    mutableListOf(
                        Proxy(
                            if (type == TYPE_HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                            socketAddress
                        )
                    )
                } catch (e: Exception) {
                    // 代理地址无效，回退到系统代理
                    delegation?.select(uri) ?: alternative.select(uri)
                }
            }
        }

        return delegation?.select(uri) ?: alternative.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegation?.connectFailed(uri, sa, ioe)
    }
}