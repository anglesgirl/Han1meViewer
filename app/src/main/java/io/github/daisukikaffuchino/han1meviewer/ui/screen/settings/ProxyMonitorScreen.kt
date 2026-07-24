package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daisukikaffuchino.han1meviewer.logic.network.ech.GoProxyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyMonitorScreen(
    onBack: () -> Unit,
) {
    val status by GoProxyManager.status.collectAsState()
    val logs by GoProxyManager.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECH 代理监控") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    // Copy button
                    IconButton(onClick = {
                        val logText = GoProxyManager.getLogsText()
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logText))
                        android.widget.Toast.makeText(context, "日志已复制 (${logText.length} 字符)", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志")
                    }
                    // Share as text button
                    IconButton(onClick = {
                        val logText = GoProxyManager.getLogsText()
                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, logText)
                        }
                        context.startActivity(android.content.Intent.createChooser(sendIntent, "分享日志"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "分享日志")
                    }
                    // Share log file button (survives crashes)
                    IconButton(onClick = {
                        val logFile = GoProxyManager.getLogFile()
                        if (logFile != null && logFile.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileProvider",
                                logFile
                            )
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, "分享日志文件 (${logFile.name})"))
                        } else {
                            android.widget.Toast.makeText(context, "没有日志文件", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Description, contentDescription = "分享日志文件")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Status card
            StatusCard(
                status = status,
                onStart = { GoProxyManager.startAsync() },
                onStop = {
                    Thread {
                        GoProxyManager.stop()
                        io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector.rebuildNetwork()
                    }.start()
                },
                onRestart = {
                    Thread { GoProxyManager.restart() }.start()
                },
                onClear = { GoProxyManager.clearLog() },
            )

            // Log section title
            Text(
                text = "日志 (${logs.size})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Log list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs) { line ->
                    LogLine(line)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: GoProxyManager.ProxyStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (status.running) Color(0xFF4CAF50) else Color(0xFF9E9E9E)),
                )
                Text(
                    text = if (status.running) "运行中" else "已停止",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Info rows
            InfoRow("端口", "127.0.0.1:${status.port}")
            InfoRow("DoH", status.dohUrl)
            if (status.startTime > 0) {
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                InfoRow("启动时间", sdf.format(Date(status.startTime)))
            }
            if (status.binaryPath.isNotBlank()) {
                InfoRow("二进制", status.binaryPath.substringAfterLast("/"))
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!status.running) {
                    FilledTonalButton(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("启动", modifier = Modifier.padding(start = 4.dp))
                    }
                } else {
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("停止", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                FilledTonalButton(onClick = onRestart) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("重启", modifier = Modifier.padding(start = 4.dp))
                }
                FilledTonalButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("清空", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LogLine(line: String) {
    val color = when {
        line.contains("ERROR", ignoreCase = true) -> Color(0xFFEF5350)
        line.contains("WARN", ignoreCase = true) -> Color(0xFFFFB74D)
        line.contains("ECH ACCEPTED") -> Color(0xFF66BB6A)
        line.contains("ECH") -> Color(0xFF42A5F5)
        line.contains("DNS:") -> Color(0xFFAB47BC)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }

    Text(
        text = line,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}
