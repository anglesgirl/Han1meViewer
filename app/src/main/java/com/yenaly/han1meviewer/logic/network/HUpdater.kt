package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.model.github.Latest
import com.yenaly.han1meviewer.util.checkNeedUpdate
import com.yenaly.han1meviewer.util.copyTo
import com.yenaly.han1meviewer.util.parseVersionCode
import com.yenaly.han1meviewer.util.runSuspendCatching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.use
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/21 021 08:28
 */
object HUpdater {

    const val TAG = "HUpdater"

    // 本分支才是 CI 出包分支（上游 main 没有出包工作流，查不到会永远提示无更新）
    const val DEFAULT_BRANCH = "ech-conscrypt"

    /**
     * Check for update
     *
     * @param forceCheck force check
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): Latest? {
        if (forceCheck || Preferences.isUpdateDialogVisible) {
            // Firebase 已移除：原来由 Remote Config 下发的开关，现在恒为 true
            if (Preferences.useCIUpdateChannel) {
                // CI 通道：所有发布里取**版本号最大**的那个（CI 每次成功构建都会发一个，含预发布）。
                //
                // ⚠️ 两个坑都是实测出来的：
                //  1) 不能只看列表第一条 —— GitHub 的返回顺序不可靠（本仓库几个 release 的
                //     created_at 完全相同，旧版 v1.0.4 会排在最新构建前面）。
                //  2) 参与比较的 tag 必须能解析出版本号：老 release 的 tag 形如 `v1.0.4-26083016`，
                //     放进去会被当成 Int.MAX_VALUE → 永远"有更新"。
                // 另外不走 workflow artifacts：下载 artifact 需要 token，App 里没有可用的
                //（CI 的临时 token 早过期），而发布资产匿名可下、还能走镜像加速。
                val releases = runSuspendCatching {
                    HanimeNetwork.githubService.getReleases()
                }.getOrNull().orEmpty()
                val newest = releases.asSequence()
                    .filter { !it.draft && it.assets.isNotEmpty() }
                    .mapNotNull { rel -> parseVersionCode(rel.tagName)?.let { code -> code to rel } }
                    .maxByOrNull { it.first }
                    ?: return null
                val (versionCode, rel) = newest
                if (BuildConfig.VERSION_CODE >= versionCode) return null
                val asset = rel.assets.first()
                return Latest(
                    version = rel.tagName,
                    changelog = rel.body.ifBlank { rel.name },
                    downloadLink = asset.browserDownloadURL,
                    nodeId = asset.nodeID,
                )
            } else {
                val ver = HanimeNetwork.githubService.getLatestVersion()
                val asset = ver.assets.firstOrNull() ?: return null
                if (checkNeedUpdate(ver.tagName)) {
                    return Latest(
                        version = ver.tagName,
                        changelog = ver.body.ifBlank { ver.name },
                        downloadLink = asset.browserDownloadURL,
                        nodeId = asset.nodeID,
                    )
                }
            }
        }
        return null
    }

    /**
     * Inject update to file
     *
     * @param url update url
     */
    suspend fun File.injectUpdate(url: String, progress: (suspend (Int, Long, Long) -> Unit)? = null) {
        var lastErr: Throwable? = null
        for (u in mirrorUrls(url)) {
            try {
                downloadInto(u, progress)
                if (u != url) Log.i(TAG, "update downloaded via mirror: $u")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "update download via $u failed: ${e.message}, try next")
                lastErr = e
            }
        }
        throw lastErr ?: IOException("update download failed: $url")
    }

    /** github release 包的下载候选：国内镜像优先，直连兜底。
     *  其他地址（不带 github.com 的）保持原链路不动。 */
    private fun mirrorUrls(url: String): List<String> {
        val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()
        return if (host == "github.com") MIRROR_PREFIXES.map { it + url } + url else listOf(url)
    }

    private suspend fun File.downloadInto(url: String, progress: (suspend (Int, Long, Long) -> Unit)? = null) {
        val res = HanimeNetwork.githubService.request(url)
        if (!res.isSuccessful) throw IOException("HTTP ${res.code()}: $url")
        if (url.endsWith("zip")) {
            Log.d(TAG, "Injecting update from zip ($url)")
            res.body()?.use { body ->
                body.byteStream().use { stream ->
                    ZipInputStream(stream).use { zip ->
                        zip.nextEntry
                        this.outputStream().use {
                            Log.i(TAG, "content length: ${body.contentLength()}")
                            // 估摸着压缩率为0.56左右，稍微估算解压后大小，防止进度卡在100%时间过长
                            zip.copyTo(it, (body.contentLength() * 1.79).toLong(), progress = progress)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Injecting update from release ($url)")
            this.outputStream().use {
                res.body()?.use { body ->
                    Log.i(TAG, "content length: ${body.contentLength()}")
                    body.byteStream().copyTo(it, body.contentLength(), progress = progress)
                }
            }
        }
    }

    /**
     * 国内加速镜像前缀（下载 github.com 的 release 包用）。
     * 依次尝试，最后一个直连兜底 —— 大陆网络下这两个镜像实测可用。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
    )
}