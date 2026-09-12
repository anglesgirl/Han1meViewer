package com.yenaly.han1meviewer

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * resolution to link map
 */
typealias ResolutionLinkMap = LinkedHashMap<String, HanimeLink>

/**
 * 如果你在其他地方看到了 Quality，那就是 Resolution，我混用了。
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2022/10/11 011 21:19
 */
class HanimeResolution {

    private val resArray = arrayOfNulls<Pair<String, HanimeLink>>(5)

    companion object {

        // 目前hanime1有的分辨率好像就這些，暫時不考慮其他分辨率

        const val RES_1080P = "1080P"
        const val RES_720P = "720P"
        const val RES_480P = "480P"
        const val RES_240P = "240P"
        const val RES_UNKNOWN = "Unknown"

        // 坏 CDN 节点换到 t33（实测可播）。只动 cdn2020 系 1~2 位数字节点：
        // 已经是 t33 的不动，其他域名/节点一概不碰。
        // （上游 Parser 里另有一套只对 AV 站生效的同类修正，两条路径结果一致、互不冲突。）
        private val BAD_CDN_HOST =
            Regex("""://t(?!33\\.)(\\d{1,2})\\.cdn2020\\.com""", RegexOption.IGNORE_CASE)

        fun normalizeCdnHost(url: String): String =
            url.replace(BAD_CDN_HOST, "://t33.cdn2020.com")
    }

    /**
     * 解析分辨率，從高到低排列。
     *
     * @param resString 分辨率
     * @param resLink 分辨率對應網址
     * @param type 例如 video/mp4
     */
    fun parseResolution(resString: String?, resLink: String, type: String? = null) {
        val mediaType = type?.toMediaTypeOrNull()?.takeIf {
            it.type.equals("video", ignoreCase = true)
        }
        val link = HanimeLink(normalizeCdnHost(resLink), mediaType?.subtype)
        when (resString) {
            RES_1080P -> resArray[0] = RES_1080P to link
            RES_720P -> resArray[1] = RES_720P to link
            RES_480P -> resArray[2] = RES_480P to link
            RES_240P -> resArray[3] = RES_240P to link
            null -> resArray[4] = RES_UNKNOWN to link
        }
    }

    fun toResolutionLinkMap(): ResolutionLinkMap {
        return resArray.filterNotNull().toMap(linkedMapOf())
    }
}

@Serializable
data class HanimeLink(
    val link: String,
    val subtype: String?,
) {
    val suffix: String
        get() = when (subtype?.lowercase()) {
            "mp4" -> "mp4"
            "mpeg" -> "mpeg"
            "x-msvideo" -> "avi"
            "3gpp" -> "3gp"
            "3gpp2" -> "3g2"
            "ogg" -> "ogv"
            "mp2t" -> "ts"
            "webm" -> "webm"
            else -> HFileManager.DEF_VIDEO_TYPE
        }
}