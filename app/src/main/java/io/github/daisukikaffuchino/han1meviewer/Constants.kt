package io.github.daisukikaffuchino.han1meviewer

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository

/**
 * 我觉得空字符串写出来太逆天了，所以搞了个常量
 */
const val EMPTY_STRING = ""

const val APP_NAME = "Han1meViewer"

// ===== 媒体 CDN =====

/**
 * 主站影片与图片使用的 CDN 主机，部分地区（如移动网络）可能无法访问。
 */
const val HANIME_MEDIA_CDN_HOST = "vdownload.hembed.com"

/**
 * 备用媒体 CDN 主机，与 [HANIME_MEDIA_CDN_HOST] 内容完全等价，
 * 仅域名不同，用于替换主 CDN 以解决部分地区无法访问的问题。
 */
const val HANIME_BACKUP_MEDIA_CDN_HOST = "1497203185.rsc.cdn77.org"

/**
 * CDN77 边缘节点区域 IP（占位符，待填入真实 IP）。
 * 两个 CDN 域名共用同一套边缘节点。
 */
data class CdnRegionNode(
    val region: String,
    val ips: List<String>,
)

val CDN_REGION_NODES = listOf(
    CdnRegionNode("日本", listOf("178.249.213.26")),
    CdnRegionNode("香港", listOf("156.146.44.89", "156.146.44.90")),
    CdnRegionNode("台湾", listOf("203.211.9.12")), // 待确认：截图显示"203.211.912"疑似少一位
    CdnRegionNode("美国", listOf("156.146.43.178", "156.146.53.36", "143.244.51.58", "89.187.187.19", "89.187.187.18", "84.17.63.146")),
    CdnRegionNode("欧洲", listOf("95.173.197.105", "95.173.197.104", "79.127.138.30", "79.112.216.203", "79.127.211.90", "212.102.56.179", "195.181.175.40", "195.181.172.3", "89.222.120.8", "84.17.50.8", "84.17.50.9", "109.61.92.54", "212.102.44.18")),
    CdnRegionNode("新加坡", listOf("79.127.235.6", "79.127.235.2")),
    // 俄罗斯暂无（测试点解析到英国 IP）
)

/**
 * 启用备用媒体 CDN 后，将回應内容中的媒体（图片 / 影片）CDN 主机替换为备用主机。
 * 如选择了区域节点 IP，则同时将域名解析到指定 IP（通过 Host 映射实现）。
 */
fun String.replaceBackupMediaCdnHost(): String {
    if (!SettingsRepository.useBackupMediaCdn) return this
    return replace(HANIME_MEDIA_CDN_HOST, HANIME_BACKUP_MEDIA_CDN_HOST, ignoreCase = true)
}

/**
 * 获取当前生效的媒体 CDN 主机。
 */
fun currentMediaCdnHost(): String =
    if (SettingsRepository.useBackupMediaCdn) HANIME_BACKUP_MEDIA_CDN_HOST
    else HANIME_MEDIA_CDN_HOST

// 标准时间格式

/* yyyy-MM-dd */
@JvmField
val LOCAL_DATE_FORMAT = LocalDate.Formats.ISO

/* yyyy-MM-dd HH:mm */
@JvmField
val LOCAL_DATE_TIME_FORMAT = LocalDateTime.Format {
    date(LocalDate.Formats.ISO); char(' ')
    hour(); char(':'); minute()
}

// 网络基本设置

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

// 設置發佈日期年份，在搜索的tag裏

/**
 * 發佈日期年份開始於
 */
const val SEARCH_YEAR_RANGE_START = 1990

/**
 * 發佈日期年份結束於
 */
const val SEARCH_YEAR_RANGE_END = BuildConfig.SEARCH_YEAR_RANGE_END

const val VIDEO_COMMENT_PREFIX = "video"

const val PREVIEW_COMMENT_PREFIX = "preview"

// base url

val HANIME_BASE_URL: String
    get() = SettingsRepository.baseUrl

/**
 * 如果添加备选网址别忘了确认[String.toVideoCode]的videoUrlRegex
 */
object HanimeConstants {
    val HANIME_HOSTNAME = arrayOf("hanime1.me","hanime1.com","hanimeone.me","javchu.com")
    val HANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/","https://javchu.com/")
    val ANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/")
}

val HANIME_LOGIN_URL: String
    get() = HANIME_BASE_URL + "login"

// github url

const val HA1_GITHUB_URL = "https://github.com/daisukiKaffuChino/Han1meViewer"

const val HA1_GITHUB_ISSUE_URL = "$HA1_GITHUB_URL/issues"

const val HA1_GITHUB_FORUM_URL = "$HA1_GITHUB_URL/discussions"
// for Shared Preference

const val LOGIN_COOKIE = "cookie"
const val SAVED_USER_ID = "saved_user_id"

const val CLOUDFLARE_COOKIE = "cf_cookie"
const val CLOUDFLARE_COOKIE_HOST = "cf_cookie_host"

const val ALREADY_LOGIN = "already_login"

// Notification

const val DOWNLOAD_NOTIFICATION_CHANNEL = "download_channel"

const val UPDATE_NOTIFICATION_CHANNEL = "update_channel"

// File

const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileProvider"
const val GETCHU_BASE_URL = "https://www.getchu.com/"
