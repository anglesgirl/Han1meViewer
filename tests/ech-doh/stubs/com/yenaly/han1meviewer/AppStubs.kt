package com.yenaly.han1meviewer

object Preferences {
    var useDoH: Boolean = true
    var dohPreset: String = "cf-gateway"
    var dohCustomUrl: String = ""
    var dohBootstrapIps: String = ""
    var dohTimeoutSeconds: Int = 5
    var useBuiltInHosts: Boolean = false
    var customHostsData: String = ""
    var mediaCdnNodeIps: List<String> = emptyList()
}

object HanimeConstants {
    @JvmField val HANIME_HOSTNAME: List<String> = listOf("hanime1.me", "hanime1.com", "hanimeone.me", "javchu.com")
}

const val HANIME_MEDIA_CDN_HOST: String = "vdownload.hembed.com"
const val HANIME_BACKUP_MEDIA_CDN_HOST: String = "1497203185.rsc.cdn77.org"
