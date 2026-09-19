package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences

data class DohPreset(
    val key: String,
    val title: String,
    val url: String,
    val bootstrapIps: List<String>,
)

/**
 * 我们实测可用的地址段。
 *
 * 实测（SNI 校验）：CF 给本 zone 分配的 172.64.229.1~254 **整段**都能服务 hanime1.me / javchu.com；
 * 而别的段（162.159.36.x / 108.162.192.x）对 javchu.com 返回 403 —— 所以 Host 只用这一段。
 * 段内随机取几个作默认值：不同用户拿到的地址不同，单点失效也不会全灭；用户可在设置里改。
 */
object NodePool {
    private const val HOST_SEGMENT = "172.64.229."
    private const val DOH_SEGMENT = "162.159.36."
    private const val PICK_COUNT = 4
    private const val LOW = 4
    private const val HIGH = 250

    private val random = java.util.Random()

    private fun pick(segment: String) =
        (LOW..HIGH).shuffled(random).take(PICK_COUNT).map { segment + it }

    /** 内置 Hosts 默认值（用于 hanime1.me 等受保护域）：首次生成后落盘，保持稳定且可被用户覆盖。 */
    fun builtInHosts(): List<String> {
        val saved = Preferences.builtInHosts
            .split(',', '\n', ';', ' ')
            .map { it.trim() }.filter { it.isNotBlank() }
        if (saved.isNotEmpty()) return saved
        return pick(HOST_SEGMENT).also { Preferences.builtInHosts = it.joinToString(",") }
    }

    /** DoH 网关引导 IP 默认值：与 [builtInHosts] 同法。 */
    fun dohBootstrapIps(): List<String> {
        val saved = Preferences.builtInDohIps
            .split(',', '\n', ';', ' ')
            .map { it.trim() }.filter { it.isNotBlank() }
        if (saved.isNotEmpty()) return saved
        return pick(DOH_SEGMENT).also { Preferences.builtInDohIps = it.joinToString(",") }
    }
}

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
            bootstrapIps = emptyList(),   // 默认由 NodePool.dohBootstrapIps() 从段内随机取
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
        return NodePool.dohBootstrapIps()
    }

    fun timeoutSeconds(): Int = Preferences.dohTimeoutSeconds.coerceIn(1, 60)

    /**
     * 生效的 DoH 地址：**用户自己填的优先**。
     *
     * 我们的网关在部分宽带（如某些联通线路）可能不可达，用户必须能换成自己的 DoH；
     * ECH/H3 仍由程序自动注入，用户只需管地址。
     */
    fun resolveUrl(): String? {
        if (!Preferences.useDoH) return null
        val custom = Preferences.dohCustomUrl.trim()
        return if (custom.isNotEmpty()) custom else selectedPreset().url
    }
}
