package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME

/**
 * 必须走 ECH 的域名（fail-closed）。
 *
 * 与 App 原有的站点常量 [HANIME_HOSTNAME] 同源 —— 单一事实来源，
 * 避免"加了新域名但 ECH 白名单忘了加"这种静默漏保护。
 *
 * ⚠️ 这份名单里的域名，拿不到 ECH 配置时**宁可连不上也不明文直连**：
 * 明文 SNI 等于把被墙域名写在脸上（用户明确要求 fail-closed）。
 */
object EchHosts {

    /** 判定是否受保护：主域及其所有子域 */
    fun isProtected(host: String): Boolean {
        val h = host.lowercase()
        return HANIME_HOSTNAME.any { h == it || h.endsWith(".$it") }
    }
}
