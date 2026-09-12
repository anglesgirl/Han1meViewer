package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.model.github.Latest
import com.yenaly.han1meviewer.util.checkNeedUpdate
import com.yenaly.han1meviewer.util.copyTo
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
                // CI 通道：取最新一次构建（CI 每次成功构建都会发一个发布，预发布也算）。
                //
                // ⚠️ 不再走 workflow artifacts：下载 artifact 需要带 token，App 里没有可用
                // token（旧版把 CI 的临时 GITHUB_TOKEN 编进包里，跑起来早过期 → 401）。
                // 发布资产是**匿名可下**的，还能直接用 gh-proxy / ghfast 镜像加速。
                val releases = runSuspendCatching {
                    HanimeNetwork.githubService.getReleases()
                }.getOrNull().orEmpty()
                val rel = releases.firstOrNull { !it.draft && it.assets.isNotEmpty() } ?: return null
                if (!checkNeedUpdate(rel.tagName)) return null
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