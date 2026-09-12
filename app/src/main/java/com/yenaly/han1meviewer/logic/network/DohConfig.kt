package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences

data class DohPreset(
    val key: String,
    val title: String,
    val url: String,
    val bootstrapIps: List<String>,
)

object DohConfig {
    /**
     * 唯一预设：自建 CF 网关 DoH —— 直控解析结果，绕开大陆 DNS 污染。
     * ECH 的配置查询（HTTPS 记录）也走它。
     *
     * 以前还有 AliDNS / DNSPod / Cloudflare / 自定义四个选项，现已移除：
     * 那些解析被墙域名时结果不可靠（甚至被污染），留着只会让用户选到"能连上但解析错"的地址。
     * 老版本存过的 key 会由 [selectedPreset] 归到这一个，**不会断网**。
     */
    val presets = listOf(
        DohPreset(
            key = "gateway",
            title = "小雅DoH",
            url = "https://tgxjjdszvu.cloudflare-gateway.com/dns-query",
            bootstrapIps = listOf("162.159.36.20", "162.159.36.5"),
        ),
    )

    /**
     * 当前生效的预设。
     * 已删除的旧 key（alidns / dnspod / cloudflare / custom）一律回落到唯一预设 ——
     * 否则会出现"设置里显示 A、实际用 B"这种查不出来的怪问题。
     */
    fun selectedPreset(): DohPreset =
        presets.firstOrNull { it.key == Preferences.dohPreset } ?: presets.first()

    fun bootstrapIps(): List<String> {
        val customBootstrapIps = Preferences.dohBootstrapIps
            .split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (customBootstrapIps.isNotEmpty()) return customBootstrapIps
        return selectedPreset().bootstrapIps
    }

    fun timeoutSeconds(): Int = Preferences.dohTimeoutSeconds.coerceIn(1, 60)

    /** 只认这一个预设，不再有"自定义 URL"分支 */
    fun resolveUrl(): String? = if (Preferences.useDoH) selectedPreset().url else null
}
