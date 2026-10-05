package com.yenaly.han1meviewer.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.logic.network.ech.EchDiagnostics

/**
 * ECH 连通性诊断对话框。
 *
 * 逐层排查：DoH 可达 → A 记录 → ECH 配置 → TCP → 普通 TLS → ECH TLS → SNI 探测，
 * 帮用户定位"连不上 ECH"具体卡在哪一步。
 */
@Composable
fun EchDiagnosticDialog(
    host: String,
    results: List<EchDiagnostics.DiagResult>,
    isRunning: Boolean,
    summary: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("ECH 连通性诊断") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "目标：$host",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(results) { r ->
                        EchDiagRow(r)
                    }
                    if (isRunning) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("诊断中…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (summary != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "结论：$summary",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isRunning) {
                Text(if (isRunning) "诊断中…" else "关闭")
            }
        },
    )
}

@Composable
private fun EchDiagRow(r: EchDiagnostics.DiagResult) {
    val (icon, color) = when (r.status) {
        EchDiagnostics.Status.PASS -> "✓" to Color(0xFF2E7D32)
        EchDiagnostics.Status.FAIL -> "✗" to Color(0xFFC62828)
        EchDiagnostics.Status.SKIP -> "–" to Color.Gray
    }
    val stepName = when (r.step) {
        "DOH_REACHABLE" -> "DoH 可达"
        "DOH_A_QUERY" -> "A 记录查询"
        "ECH_CONFIG" -> "ECH 配置"
        "TCP_CONNECT" -> "TCP 建连"
        "TLS_PLAIN" -> "普通 TLS"
        "TLS_ECH" -> "ECH TLS"
        "SNI_PROBE" -> "SNI 探测"
        else -> r.step
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = color, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(6.dp))
            Text(stepName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            if (r.latencyMs >= 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "${r.latencyMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            r.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}
