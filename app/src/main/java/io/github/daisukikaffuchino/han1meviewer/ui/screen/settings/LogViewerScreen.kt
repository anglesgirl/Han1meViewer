package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志查看器:显示 LogUtil 内存缓冲的最近日志,
 * 实时刷新,支持复制全部/导出文件/清空。用于诊断网络/ECH/卡顿问题。
 *
 * 注意:listener 必须在离开页面时注销(DisposableEffect),否则每次网络日志
 * 都会在 IO 线程刷新 Compose state + 主线程拼大字符串 → 首页滑动丢帧。
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
) {
    // 日志以拼接好的字符串缓存,避免主线程每次重组都 joinToString(最多 2000 行)。
    var logsText by remember { mutableStateOf(LogUtil.dumpLogs().joinToString("\n")) }
    var refreshTick by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    // 实时刷新:listener 只做标记;注销由 DisposableEffect 保证。
    DisposableEffect(Unit) {
        val listener = { refreshTick = refreshTick + 1 }
        LogUtil.addListener(listener)
        onDispose { LogUtil.removeListener(listener) }
    }
    // 每 1s 兜底轮询(防丢事件)。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refreshTick = refreshTick + 1
        }
    }
    // 拼接 2000 行字符串在 IO 线程做,主线程只接收结果。
    LaunchedEffect(refreshTick) {
        logsText = withContext(Dispatchers.Default) {
            LogUtil.dumpLogs().joinToString("\n")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部操作栏:复制全部 + 导出文件 + 清空 + 返回
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                if (logsText.isNotBlank()) {
                    copyToClipboard(context, logsText)
                    SonnerToast.success(R.string.logs_copied)
                }
            }) {
                Text(stringResource(R.string.copy_logs))
            }
            TextButton(onClick = { exportLogs(context, logsText) }) {
                Text(stringResource(R.string.export_logs))
            }
            TextButton(onClick = {
                LogUtil.clearLogs()
                logsText = ""
            }) {
                Text(stringResource(R.string.clear_logs))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }

        // 日志内容
        SelectionContainer {
            Text(
                text = if (logsText.isBlank()) {
                    stringResource(R.string.logs_empty)
                } else {
                    logsText
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("logs", text))
}

/** 导出日志为 txt 到系统下载目录(MediaStore,Android 10+),并弹出分享。 */
private fun exportLogs(context: Context, text: String) {
    if (text.isBlank()) {
        SonnerToast.warning(R.string.logs_empty)
        return
    }
    val fileName =
        "Han1meViewer_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
    runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("openOutputStream failed")
        uri
    }.onSuccess { uri ->
        SonnerToast.success(R.string.logs_exported)
        // 分享到 Telegram/微信等,方便直接发送日志。
        runCatching {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, context.getString(R.string.export_logs)))
        }
    }.onFailure { e ->
        SonnerToast.error("${context.getString(R.string.export_logs_failed)}: ${e.message}")
    }
}
