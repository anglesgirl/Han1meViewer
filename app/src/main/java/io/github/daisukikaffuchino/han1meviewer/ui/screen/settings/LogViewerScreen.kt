package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.utils.LogUtil
import kotlinx.coroutines.delay

/**
 * 应用内日志查看器:显示 LogUtil 内存缓冲的最近日志,
 * 实时刷新,支持复制/清空。用于诊断网络/ECH 问题。
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
) {
    var logs by remember { mutableStateOf(LogUtil.dumpLogs()) }
    var refreshTick by remember { mutableStateOf(0L) }

    // 实时刷新:监听 LogUtil + 每 1s 兜底轮询。
    LaunchedEffect(Unit) {
        val listener = { refreshTick = refreshTick + 1 }
        LogUtil.addListener(listener)
        while (true) {
            delay(1000)
            refreshTick = refreshTick + 1
        }
    }
    LaunchedEffect(refreshTick) {
        logs = LogUtil.dumpLogs()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部操作栏:清空 + 返回
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = {
                LogUtil.clearLogs()
                logs = LogUtil.dumpLogs()
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
                text = if (logs.isEmpty()) {
                    stringResource(R.string.logs_empty)
                } else {
                    logs.joinToString("\n")
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
