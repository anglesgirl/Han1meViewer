package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.model.github.CommitComparison
import com.yenaly.han1meviewer.logic.model.github.Latest
import com.yenaly.han1meviewer.util.EchStats
import com.yenaly.han1meviewer.util.checkNeedUpdate
import com.yenaly.han1meviewer.util.copyTo
import com.yenaly.han1meviewer.util.runSuspendCatching
import okhttp3.HttpUrl
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

    // 本分支才是 CI 出包分支(main 无工作流,查不到会永远无更新)
    const val DEFAULT_BRANCH = "go-misaka"

    /**
     * Regex to match multiple line feeds to a single line feed
     */
    private val linefeedRegex = Regex("\\n{2,}")

    /**
     * Check for update
     *
     * @param forceCheck force check
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): Latest? {
        if (forceCheck || Preferences.isUpdateDialogVisible) {
            if (Preferences.useCIUpdateChannel) {
                val curSha = BuildConfig.COMMIT_SHA
                // 特殊情况下才用注释部分，一般情况下 branch 都是固定的，要不然多一次
                // request 会对我的 API Token 造成负担。
                // val apiReq = request(HA1_GITHUB_API_URL)
                // val branch = apiReq.body?.string()?.let(::JSONObject)?.getString("default_branch")
                //     ?: return null
                val workflowRun = HanimeNetwork.githubService.getWorkflowRuns()
                    .workflowRuns.firstOrNull() ?: return null
                val shortSha = workflowRun.headSha.take(7)
                if (shortSha != curSha) {
                    val artifacts =
                        HanimeNetwork.githubService.getArtifacts(workflowRun.artifactsUrl)
                    val archiveUrl = artifacts.downloadLink
                    val nodeId = artifacts.nodeId
                    val changelog = runSuspendCatching {
                        HanimeNetwork.githubService.getCommitComparison(
                            curSha = curSha,
                            latestSha = shortSha
                        ).commits.toChangelogPrettyString()
                    }.getOrNull() ?: workflowRun.title
                    EchStats.event("update_available", mapOf("channel" to "ci", "tag" to shortSha))
                    return Latest("$shortSha (CI)", changelog, archiveUrl, nodeId)
                }
            } else {
                val ver = HanimeNetwork.githubService.getLatestVersion()
                val isNeeded = checkNeedUpdate(ver.tagName)
                if (isNeeded) {
                    EchStats.event("update_available", mapOf("channel" to "release", "tag" to ver.tagName))
                    return Latest(
                        ver.tagName, ver.body,
                        ver.assets.first().browserDownloadURL,
                        ver.assets.first().nodeID
                    )
                }
            }
        }
        return null
    }

    /**
     * Inject update to file.
     *
     * github.com 的 release 包先走国内镜像,镜像失败再直连;
     * 其他地址(artifact zip 等)保持原链路。
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

    /** github release 包的下载候选:镜像优先,直连兜底。 */
    private fun mirrorUrls(url: String): List<String> {
        val host = runCatching { HttpUrl.get(url).host }.getOrNull()
        return if (host == "github.com") {
            listOf(MIRROR_PREFIX + url, url)
        } else {
            listOf(url)
        }
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
     * This function is used to filter out commits that are not authored by the user.
     */
    private val CommitComparison.Commit.CommitDetail.CommitAuthor.isAuthorShouldIgnore: Boolean
        get() = name.contains("dependabot")

    private fun List<CommitComparison.Commit>.toChangelogPrettyString(): String {
        return filterNot { commit -> commit.commit.author.isAuthorShouldIgnore }
            .distinct().reversed().joinToString("\n\n") { commit ->
                val message = commit.commit.message.replace(linefeedRegex, "\n")
                "↓ (@${commit.commit.author.name})\n$message"
            }
    }

    companion object {
        private const val MIRROR_PREFIX = "https://gh-proxy.com/"
    }
}
}