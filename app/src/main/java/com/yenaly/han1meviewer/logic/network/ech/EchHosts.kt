package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME

/**
 * ECH 的域名策略：**所有网络都先试 ECH，能走就走**，并区分「能不能降级」。
 *
 * 两档语义，不要混用：
 *
 * 1. [shouldTryEch] —— **全部域名**都返回 true。也就是说任意域名都会尝试注入
 *    ECHConfigList。能走 ECH 的（Cloudflare 系站点，含挂了 `cloudflare-ech.com`
 *    custom hostname 的站）就享受 ECH；走不了的靠 [ConscryptEch.markEchUnavailable]
 *    记下来，下一次直接明文，不再浪费一次失败握手。
 *
 *    为什么不只做白名单：ECH 的价值是**隐藏 SNI**，第三方图床/CDN 同样在被 SNI
 *    阻断的网络里会挂（实测用户报障日志里 `tutu1.space` 就是这类域名）。
 *
 * 2. [isCoreDomain] —— 站点主域（[HANIME_HOSTNAME]），**不允许降级**。
 *    这类域名是明确被墙的：降级成明文 = 把 SNI 写在脸上 = 立刻被 RST，
 *    功能和安全两头都输。所以拿不到 ECH 配置时宁可连不上（fail-closed，
 *    用户明确要求）。普通域名没有这个顾虑 —— 它们本来就在明文访问。
 */
object EchHosts {

    /**
     * 是否对该域名尝试 ECH。
     *
     * 恒为 true：全量尝试。失败降级由 [ConscryptEch.markEchUnavailable] 负责，
     * 不在这里做静态白名单 —— 白名单永远追不上真实世界的域名。
     */
    fun shouldTryEch(@Suppress("UNUSED_PARAMETER") host: String): Boolean = true

    /**
     * 是否为核心域名（主域及其所有子域）：**必须走 ECH，不允许降级明文**。
     *
     * 与 App 原有的站点常量 [HANIME_HOSTNAME] 同源 —— 单一事实来源，
     * 避免"加了新域名但 ECH 名单忘了加"这种静默漏保护。
     */
    fun isCoreDomain(host: String): Boolean {
        val h = host.lowercase()
        return HANIME_HOSTNAME.any { h == it || h.endsWith(".$it") }
    }

    /** 旧名保留：语义等同 [shouldTryEch]（全量尝试）。 */
    fun isProtected(host: String): Boolean = shouldTryEch(host)
}
