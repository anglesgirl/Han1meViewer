package com.yenaly.han1meviewer.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.FILE_PROVIDER_AUTHORITY
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.ech.EchHosts
import com.yenaly.han1meviewer.logic.network.ech.EchHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无 ADB 日志导出:抓本进程 logcat + 代理状态 + 脱敏配置,分享出去。
 * logcat 只读本进程(--pid),无需任何权限。
 * Cookie/密码/Token 绝不写入。
 */
object LogExporter {

    private const val TAG = "LogExporter"

    suspend fun collect(context: Context): File = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("=== Han1meViewer ECH 诊断 ===")
        sb.appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("version=${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
        sb.appendLine("android=${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        sb.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("baseUrl=${Preferences.baseUrl}")
        sb.appendLine("dohPreset=${Preferences.dohPreset} useDoH=${Preferences.useDoH}")
        sb.appendLine("echReady=${EchHttp.isReady}")
        sb.appendLine("echProtected=${EchHosts.isProtected(Preferences.baseUrl)}")
        sb.appendLine("=== logcat(pid=${android.os.Process.myPid()}) ===")
        sb.appendLine(readOwnLogcat())
        val name = "han1me-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        File(context.cacheDir, name).apply { writeText(sb.toString()) }
    }

    private fun readOwnLogcat(): String = runCatching {
        val pid = android.os.Process.myPid().toString()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-t", "4000", "-v", "time"))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out.ifBlank { "(logcat 为空)" }
    }.getOrElse { "logcat 读取失败: ${it.message}" }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }
}
