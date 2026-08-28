package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME

/**
 * 哪些域名必须用 ECH 才允许连接。
 *
 * 取舍依据：站点主域在受限网络下会被 SNI 阻断，明文 SNI 直连既连不上、又会暴露访问目标，
 * 因此对这些域名采用 fail-closed —— 拿不到 ECHConfig 就拒绝连接，而不是退化成明文。
 * 其余域名（GitHub、getchu、图片 CDN 等）走机会性 ECH：有就用，没有也放行。
 */
object EchPolicy {

    /** 必须 ECH 的域名（含子域）。 */
    private val mandatoryHosts: Set<String> = HANIME_HOSTNAME.map { it.lowercase() }.toSet()

    fun requiresEch(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return mandatoryHosts.any { normalized == it || normalized.endsWith(".$it") }
    }
}
