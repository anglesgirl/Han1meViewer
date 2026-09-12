package com.yenaly.han1meviewer.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.FILE_PROVIDER_AUTHORITY
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.model.github.Latest
import java.io.File

val Context.updateFile: File get() = File(applicationContext.cacheDir, "update.apk")

/**
 * 从 tag 里取 `+<版本号>`。取不到返回 null。
 *
 * 例：`Han1meViewer-v1.0.8-ci+26091211` → 26091211；
 * 老 release 的 tag 形如 `v1.0.4-26083016` 没有 `+` → null。
 * ⚠️ 直接 `substringAfter("+").toIntOrNull()` 会因后缀（`-ech-conscrypt`）解析失败，
 * 调用方若按 Int.MAX_VALUE 兜底就会"永远提示有更新"，所以这里要精确取数字。
 */
fun parseVersionCode(versionName: String): Int? =
    Regex("\\+(\\d+)").find(versionName)?.groupValues?.get(1)?.toIntOrNull()
        ?: versionName.substringAfter("+", "").toIntOrNull()

fun checkNeedUpdate(versionName: String): Boolean {
    val latestVersionCode = parseVersionCode(versionName) ?: Int.MAX_VALUE
    return BuildConfig.VERSION_CODE < latestVersionCode
}

internal fun Context.getUpdateIfExists(latest: Latest): File? {
    val nodeId = Preferences.updateNodeId
    return updateFile.takeIf { file ->
        !BuildConfig.DEBUG && file.exists() && nodeId.isNotEmpty() && nodeId == latest.nodeId
    }
}

suspend fun Context.installApkPackage(file: File) {
    val canInstall = requestInstallPermission()
    if (canInstall) {
        val uri = FileProvider.getUriForFile(this.applicationContext, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }
}
