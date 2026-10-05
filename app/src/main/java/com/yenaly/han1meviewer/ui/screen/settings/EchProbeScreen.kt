package com.yenaly.han1meviewer.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenaly.han1meviewer.logic.network.ech.EchProbe
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ECH 探针屏（对标 "ECH-H3 探针" App）。
 *
 * - 上半：本机网络检测（ECH 支持 / ECH 真正生效 / 对照）
 * - 下半：高级对照测试（指定域名 / IP / 强制注入 ECH）
 * - 底部：实时日志
 */
@Composable
fun EchProbeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(false) }
    var conclusion by remember { mutableStateOf<String?>(null) }

    // 高级测试输入
    var customHost by remember { mutableStateOf("javchu.com") }
    var customIps by remember { mutableStateOf("") }
    var customEch by remember { mutableStateOf("") }

    // 快捷域名
    val quickHosts = listOf("JAVCHU.COM", "HANIME1.ME", "I.PXIMG.NET", "API.BGM.TV")

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    fun buildLogText(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("== ECH 探针日志 ==")
            appendLine("时间：$ts")
            conclusion?.let { appendLine(it) }
            appendLine("".padEnd(40, '-'))
            logs.forEach { appendLine(it) }
        }
    }

    fun copyLogs() {
        val text = buildLogText()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ECH 探针日志", text))
        GlobalToasts.show("日志已复制", level = GlobalToasts.ToastLevel.SUCCESS)
    }

    fun shareLogs() {
        val text = buildLogText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "ECH 探针日志")
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }

    fun runProbe(block: suspend () -> EchProbe.ProbeResult) {
        if (isRunning) return
        logs.clear()
        conclusion = null
        isRunning = true
        scope.launch(Dispatchers.IO) {
            val result = block()
            withContext(Dispatchers.Main) {
                conclusion = "结论：" + if (result.ok) "✓ 通过" else "✗ 未通过"
                isRunning = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()  // 避开状态栏
            .padding(16.dp)
    ) {
        // ---- 本机网络检测 ----
        // 用当前输入框的域名测，更贴近实际使用（而不是写死的 cloudflare-ech.com）
        Button(
            onClick = {
                val h = customHost.trim().ifEmpty { "javchu.com" }
                runProbe {
                    EchProbe.probeNetwork(testHost = h) { line ->
                        scope.launch(Dispatchers.Main) { logs.add(line) }
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text("检测本机网络（ECH）")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 高级对照测试（可折叠，默认收起） ----
        var showAdvanced by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showAdvanced) "▲ 收起高级对照测试" else "▼ 高级：对照测试（指定域名 / IP / ECH 注入）")
        }
        if (showAdvanced) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {

                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = { Text("域名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 快捷域名：用 FlowRow 自动换行，避免挤成竖排
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    quickHosts.forEach { h ->
                        androidx.compose.material3.TextButton(
                            onClick = { customHost = h.lowercase() },
                        ) { Text(h, fontSize = 11.sp, maxLines = 1) }
                    }
                }
                OutlinedTextField(
                    value = customIps,
                    onValueChange = { customIps = it },
                    label = { Text("指定 IP（留空=自动；多个逗号分隔）", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = customEch,
                    onValueChange = { customEch = it },
                    label = { Text("强制注入 ECH（base64，留空=用 DoH 的 ech=）", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val h = customHost.trim()
                        if (h.isEmpty()) return@Button
                        runProbe {
                            EchProbe.probeCustom(h, customIps, customEch) { line ->
                                scope.launch(Dispatchers.Main) { logs.add(line) }
                            }
                        }
                    },
                    enabled = !isRunning,
                ) {
                    Text("开始对照测试")
                }
            }
        }
        } // 关闭 if (showAdvanced)

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 结论（置顶高亮卡片） ----
        conclusion?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ---- 日志 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("日志", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { copyLogs() },
                enabled = logs.isNotEmpty(),
            ) { Text("复制", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { shareLogs() },
                enabled = logs.isNotEmpty(),
            ) { Text("分享", fontSize = 12.sp) }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                if (logs.isEmpty() && !isRunning) {
                    item {
                        Text("点上方按钮开始检测",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
