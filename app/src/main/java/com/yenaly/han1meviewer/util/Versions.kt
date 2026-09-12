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

fun checkNeedUpdate(versionName: String): Boolean {
    // tag 形如 `Han1meViewer-v1.0.8-ci+2609121210`（旧版还带 `-ech-conscrypt` 后缀），
    // 所以只取 "+" 后面的**数字**：直接 substringAfter("+").toIntOrNull() 会因后缀解析失败，
    // 回落成 Int.MAX_VALUE → 永远提示"有更新"（旧版就踩过）。
    val latestVersionCode = Regex("\\+(\\d+)")
        .find(versionName)?.groupValues?.get(1)?.toIntOrNull()
        ?: versionName.substringAfter("+", "").toIntOrNull()
        ?: Int.MAX_VALUE
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
