package com.yenaly.han1meviewer

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char

/**
 * 我觉得空字符串写出来太逆天了，所以搞了个常量
 */
const val EMPTY_STRING = ""

const val APP_NAME = "Han1meViewer"

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

@JvmField
val HANIME_BASE_URL = Preferences.baseUrl

/**
 * 如果添加备选网址别忘了确认[String.toVideoCode]的videoUrlRegex
 */
object HanimeConstants {
    val HANIME_HOSTNAME = arrayOf("hanime1.me","hanime1.com","hanimeone.me","javchu.com")
    val HANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/","https://javchu.com/")
    val ANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/")
}

@JvmField
val HANIME_LOGIN_URL = HANIME_BASE_URL + "login"

// 媒體 CDN

/**
 * 主站影片與圖片使用的 CDN 主機，部分地區可能無法訪問。
 */
const val HANIME_MEDIA_CDN_HOST = "vdownload.hembed.com"

/**
 * 備用媒體 CDN 主機，用於替換 [HANIME_MEDIA_CDN_HOST]。
 */
const val HANIME_BACKUP_MEDIA_CDN_HOST = "1497203185.rsc.cdn77.org"

/**
 * 啟用備用媒體 CDN 後，將回應內容中的媒體（圖片 / 影片）CDN 主機替換為備用主機。
 */
fun String.replaceBackupMediaCdnHost(): String {
    if (!Preferences.useBackupMediaCdn) return this
    return replace(HANIME_MEDIA_CDN_HOST, HANIME_BACKUP_MEDIA_CDN_HOST, ignoreCase = true)
}

/**
 * CDN 区域节点：预设的 CDN77 边缘节点 IP。
 * 两个媒体 CDN 域名（[HANIME_MEDIA_CDN_HOST] / [HANIME_BACKUP_MEDIA_CDN_HOST]）
 * 内容完全等价，共用同一套节点。
 */
data class CdnRegionNode(val region: String, val ips: List<String>)

val CDN_REGION_NODES = listOf(
    CdnRegionNode("日本", listOf("178.249.213.26")),
    CdnRegionNode("香港", listOf("156.146.44.89", "156.146.44.90")),
    CdnRegionNode("台湾", listOf("203.211.9.12")),
    CdnRegionNode("俄罗斯", listOf("37.19.202.45")),
    CdnRegionNode("美国", listOf("156.146.43.178", "156.146.53.36", "143.244.51.58", "89.187.187.19", "89.187.187.18", "84.17.63.146")),
    CdnRegionNode("欧洲", listOf("95.173.197.105", "95.173.197.104", "79.127.138.30", "79.112.216.203", "79.127.211.90", "212.102.56.179", "195.181.175.40", "195.181.172.3", "89.222.120.8", "84.17.50.8", "84.17.50.9", "109.61.92.54", "212.102.44.18")),
    CdnRegionNode("新加坡", listOf("79.127.235.6", "79.127.235.2")),
)

// github url

const val HA1_GITHUB_URL = "https://github.com/anglesgirl/Han1meViewer"

const val HA1_GITHUB_ISSUE_URL = "$HA1_GITHUB_URL/issues"

const val HA1_GITHUB_FORUM_URL = "$HA1_GITHUB_URL/discussions"

const val HA1_GITHUB_RELEASES_URL = "$HA1_GITHUB_URL/releases"

const val HA1_GITHUB_API_URL = "https://api.github.com/repos/anglesgirl/Han1meViewer/"
// ⚠️ 已停用：这是上游作者（misaka10032w）的 Firebase 项目，本 fork 没有自己的 RTDB。
// HomePageViewModel.fetchAnnouncementsFromFirebase() 已改为直接返回空列表，不再请求此处。
// 如需恢复公告功能：把这里改成自己的 RTDB 地址，并恢复 fetchAnnouncementsFromFirebase() 的原实现。
const val FIREBASE_REALTIME_DATABASE = "https://han1meviewer-86e5f-default-rtdb.asia-southeast1.firebasedatabase.app/"
// for Shared Preference

const val LOGIN_COOKIE = "cookie"
const val SAVED_USER_ID = "saved_user_id"

const val CLOUDFLARE_COOKIE = "cf_cookie"

const val ALREADY_LOGIN = "already_login"

// Notification

const val DOWNLOAD_NOTIFICATION_CHANNEL = "download_channel"

const val UPDATE_NOTIFICATION_CHANNEL = "update_channel"

// File

const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileProvider"
const val GETCHU_BASE_URL = "https://www.getchu.com/"
