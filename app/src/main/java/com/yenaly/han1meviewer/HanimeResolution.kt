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
            Regex("""://t(?!33\.)(\d{1,2})\.cdn2020\.com""", RegexOption.IGNORE_CASE)

        /**
         * 被运营商**按域名封锁**、但同一 CDN 还有可用备用域名的 host。
         *
         * 实测：中国移动按 SNI 单独封了 `vdownload.hembed.com`（CDN77 域名，图片与视频都挂在这），
         * 而 CDN77 给它分配的备用域名 `1497203185.rsc.cdn77.org` 未被封 ——
         * 两者共用同一张证书（SAN 同时覆盖两者）、鉴权参数不绑定 Host，
         * 所以直接换 Host 就能拿到同样内容（图片侧已按此修复并真机验证）。
         *
         * ⚠️ 只放**已实测**有可用备用域名的 host。站方自建的 `vdownload-8.hembed.com`
         * 证书只含它自己，换了会直接证书错误 —— **绝不能进这张表**。
         */
        private val BLOCKED_HOST_ALIASES = mapOf(
            "vdownload.hembed.com" to "1497203185.rsc.cdn77.org",
        )

        /**
         * 修正播放/加载用的 CDN 域名。
         *
         * ⚠️ 为什么这里必须单独做一遍：视频播放器用的是
         * `androidx.media3.datasource.DefaultHttpDataSource`（底层 HttpURLConnection），
         * **完全不经过 OkHttp** —— OkHttp 那侧的拦截器（ECH / DoH / CDN Host 改写）
         * 一个都覆盖不到播放链路，只能在这一层换域名。
         *
         * 换域名后响应头里的 Content-Type 可能变成 octet-stream
         * （CDN77 按 Host 配置 MIME），但播放器靠文件头 sniff、不看 Content-Type，
         * 实测可直接播放。
         */
        fun normalizeCdnHost(url: String): String {
            var out = url.replace(BAD_CDN_HOST, "://t33.cdn2020.com")
            BLOCKED_HOST_ALIASES.forEach { (blocked, alt) ->
                out = out.replace("://$blocked", "://$alt")
            }
            return out
        }
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