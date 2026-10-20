package com.yenaly.han1meviewer

object Preferences {
    private var dohUrl: String = "https://trusted.invalid/dns-query"
    var useBuiltInHosts: Boolean = false
    var customHostsData: String = ""
    var dohUrlReads: Int = 0
    var dohCustomUrl: String
        get() { dohUrlReads++; return dohUrl }
        set(value) { dohUrl = value }
    var mediaCdnNodeIps: List<String> = emptyList()
}

object HanimeConstants {
    @JvmField val HANIME_HOSTNAME: List<String> = listOf("hanime1.me", "hanime1.com", "hanimeone.me", "javchu.com")
}

const val HANIME_MEDIA_CDN_HOST: String = "vdownload.hembed.com"
const val HANIME_BACKUP_MEDIA_CDN_HOST: String = "1497203185.rsc.cdn77.org"

object StubCounters {
    @JvmField var selectedPreset: Int = 0
    @JvmField var bootstrapIps: Int = 0
    @JvmField var timeoutSeconds: Int = 0
}
