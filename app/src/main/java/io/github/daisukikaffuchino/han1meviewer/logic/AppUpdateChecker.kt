package io.github.daisukikaffuchino.han1meviewer.logic

import android.util.Base64
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.decodeFromStringByBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val updateDescription: String,
    val forceUpdate: Boolean,
)

data class AppUpdateCheckResult(
    val updateInfo: AppUpdateInfo? = null,
    val announcement: Announcement? = null,
)

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object NoUpdate : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
}

@Serializable
private data class AppUpdatePayload(
    val versionName: String? = null,
    val versionCode: Int = 0,
    val downloadUrl: String? = null,
    val updateDescription: String = "",
    val forceUpdate: Boolean = false,
    val isShowAnnouncement: Boolean = false,
    val announcement: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val ENCODED_UPDATE_URL =
        "aHR0cHM6Ly9obm0tMTI1ODY2NDI3Ni5jb3MuYXAtc2hhbmdoYWkubXlxY2xvdWQuY29tL3VwZGF0ZS5qc29u"
    private const val ENCODED_UPDATE_REFERER = "aG5tdmlld2VydXAuY29t"
    private const val CURRENT_VERSION_CODE = 260804

    /** GitHub Releases API:优先用它检查更新(api.github.com 大陆裸连可用)。 */
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest"
    private const val GITHUB_DOWNLOAD_BASE =
        "https://github.com/anglesgirl/Han1meViewer/releases/download"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 更新检查也走 ECH 代理(COS/GitHub API 不支持 ECH → 自动降级 TLS)
            .addInterceptor(io.github.daisukikaffuchino.han1meviewer.logic.network.EchInterceptor())
            .build()
    }

    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val cachedJson = SettingsRepository.current.cachedUpdateJson

        // 1. 优先 GitHub Releases API(版本/下载地址完全由本 fork 控制,
        //    api.github.com 大陆裸连可用;我们的 release 自动发布到 GitHub)
        val gitHubResult = runCatching { requestUpdateFromGitHub() }
            .onFailure { LogUtil.e(TAG, "GitHub update check failed", it) }
            .getOrNull()
        if (gitHubResult != null) return@withContext gitHubResult

        // 2. 回退:上游 COS update.json(保留,部分网络 api.github.com 不可达)
        val responseJson = runCatching { requestUpdateJson() }
            .onFailure { LogUtil.e(TAG, "COS update check failed", it) }
            .getOrNull()

        if (responseJson != null) SettingsRepository.setCachedUpdateJson(responseJson)

        val jsonToUse = responseJson ?: cachedJson
        if (responseJson == null) {
            jsonToUse?.let { LogUtil.d(TAG, "Using stale update JSON: $it") }
        }
        jsonToUse.toUpdateCheckResult()
    }

    suspend fun ignoreUpdate(versionCode: Int) = SettingsRepository.setIgnoredVersionCode(versionCode)

    /**
     * 从 GitHub Releases API 获取最新 release:
     * - tag_name → 版本号(如 v26.3.2 → 26.3.2)
     * - assets 里的 arm64 APK → 下载地址
     * - body → 更新说明
     * 版本比较用 versionCode:tag 里 vX.Y.Z 转 XYYZZ(如 26.3.2 → 260302),
     * 与本地 CURRENT_VERSION_CODE 格式不一致时回退 COS。
     */
    private fun requestUpdateFromGitHub(): AppUpdateCheckResult? {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Han1meViewer")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                LogUtil.d(TAG, "GitHub API HTTP ${response.code}")
                return null
            }
            val body = response.body.string()
            val json = runCatching { jsonParser.parseToJsonElement(body).jsonObject }
                .onFailure { LogUtil.e(TAG, "GitHub API parse failed", it) }
                .getOrNull() ?: return null

            val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val versionName = tagName.removePrefix("v").trim()
            if (versionName.isBlank()) return null

            // 语义化版本比较:v26.3.2 > 本地 26.3.1 才算更新
            val currentName = BuildConfig.VERSION_NAME
            if (compareVersions(versionName, currentName) <= 0) {
                LogUtil.d(TAG, "GitHub latest ${versionName} <= current ${currentName}, no update")
                return null
            }

            // 找 APK asset(release 只发布一个 arm64 APK,直接按后缀匹配;
            // 若将来有多个,优先含 arm64 字样的)
            val assets = json["assets"]?.jsonArray ?: return null
            var apkName: String? = null
            for (asset in assets) {
                val name = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: continue
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (name.contains("arm64", ignoreCase = true)) {
                    apkName = name
                    break
                }
                if (apkName == null) apkName = name
            }
            val downloadUrl = apkName?.let { "$GITHUB_DOWNLOAD_BASE/$tagName/$it" } ?: return null

            val description = json["body"]?.jsonPrimitive?.contentOrNull?.take(500).orEmpty()

            LogUtil.i(TAG, "GitHub update found: ${versionName} -> $downloadUrl")
            return AppUpdateCheckResult(
                updateInfo = AppUpdateInfo(
                    versionName = versionName,
                    versionCode = CURRENT_VERSION_CODE + 1, // 仅用于忽略逻辑占位
                    downloadUrl = downloadUrl,
                    updateDescription = description,
                    forceUpdate = false,
                ),
            )
        }
    }

    /** 语义化版本比较:1.2.3 > 1.2.2。返回 a 与 b 的比较结果。 */
    private fun compareVersions(a: String, b: String): Int {
        fun parse(s: String): List<Int> =
            s.split(".").mapNotNull { it.toIntOrNull() }
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun requestUpdateJson(): String {
        val request = Request.Builder()
            .url(ENCODED_UPDATE_URL.decodeFromStringByBase64(Base64.NO_WRAP))
            .header(
                "Referer",
                ENCODED_UPDATE_REFERER.decodeFromStringByBase64(Base64.NO_WRAP)
            )
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Update check failed with HTTP ${response.code}" }
            response.body.string().also { json ->
                LogUtil.d(TAG, "Update response JSON: $json")
            }
        }
    }

    private fun String?.toUpdateCheckResult(): AppUpdateCheckResult {
        if (this.isNullOrBlank()) return AppUpdateCheckResult()
        return runCatching {
            val payload = jsonParser.decodeFromString<AppUpdatePayload>(this)
            AppUpdateCheckResult(
                updateInfo = payload.toAvailableUpdateOrNull(),
                announcement = payload.toAnnouncementOrNull(),
            )
        }.onFailure {
            LogUtil.e(TAG, "Invalid update JSON", it)
        }.getOrDefault(AppUpdateCheckResult())
    }

    private fun AppUpdatePayload.toAvailableUpdateOrNull(): AppUpdateInfo? {
        val versionName = versionName?.trim().orEmpty()
        // 下载地址重写:远程 update.json 指向上游 daisukiKaffuChino/Han1meViewer,
        // 但本 fork 的发布在 anglesgirl/Han1meViewer,重写让用户下载到正确的包。
        val rawDownloadUrl = downloadUrl?.trim().orEmpty()
        val downloadUrl = rewriteDownloadUrl(rawDownloadUrl)
        if (versionName.isBlank() || versionCode <= 0 || downloadUrl.isBlank()) return null
        if (downloadUrl.toHttpUrlOrNull() == null) {
            LogUtil.e(TAG, "downloadUrl is invalid")
            return null
        }

        val currentVersionCode = CURRENT_VERSION_CODE
        val ignoredVersionCode = SettingsRepository.current.ignoredVersionCode
        return AppUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            updateDescription = updateDescription,
            forceUpdate = forceUpdate,
        ).takeIf {
            it.versionCode > currentVersionCode &&
                (it.forceUpdate || it.versionCode != ignoredVersionCode)
        }
    }

    /** 把上游 GitHub 下载地址重写为本 fork 的 release 页。 */
    private fun rewriteDownloadUrl(url: String): String {
        if (url.isBlank()) return url
        // 只重写指向上游仓库的 release 链接
        if (url.contains("github.com/daisukiKaffuChino/Han1meViewer", ignoreCase = true) ||
            url.contains("github.com/daisukiKaffuChino/HanimeViewer", ignoreCase = true)
        ) {
            val rewritten = url.replace(
                Regex("github\\.com/[^/]+/[^/]+", RegexOption.IGNORE_CASE),
                "github.com/anglesgirl/Han1meViewer"
            )
            LogUtil.i(TAG, "downloadUrl rewritten: $url -> $rewritten")
            return rewritten
        }
        return url
    }

    private fun AppUpdatePayload.toAnnouncementOrNull(): Announcement? {
        val content = announcement.trim()
        if (!isShowAnnouncement || content.isBlank()) return null
        return Announcement(
            title = applicationContext.getString(R.string.update_announcement_title),
            content = content,
            isActive = true,
        )
    }
}
