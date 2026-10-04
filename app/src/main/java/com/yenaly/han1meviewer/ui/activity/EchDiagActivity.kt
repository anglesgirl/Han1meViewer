package com.yenaly.han1meviewer.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.ech.ConscryptEch
import com.yenaly.han1meviewer.logic.network.ech.EchDoh
import com.yenaly.han1meviewer.logic.network.ech.EchLogReporter
import com.yenaly.han1meviewer.logic.network.ech.EchTrace
import com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.util.concurrent.TimeUnit

/**
 * ECH 诊断页：排查"没有网"问题。
 *
 * 入口：网络设置 → 调试 → ECH 诊断。
 * 所有测试跑在 IO 线程，结果同时打到 [EchTrace]（adb logcat 可抓）。
 */
class EchDiagActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HanimeTheme {
                EchDiagScreen(onClose = { finish() })
            }
        }
    }
}

private data class DiagItem(
    val title: String,
    val status: String,      // 实时状态（一行）
    val detail: String = "", // 测试详情（多行）
    val testing: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EchDiagScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var conscrypt by remember { mutableStateOf(DiagItem("Conscrypt ECH", "未测试")) }
    var dohResolve by remember { mutableStateOf(DiagItem("EchDoh.resolve(hanime1.me)", "未测试")) }
    var dohEndpoint by remember { mutableStateOf(DiagItem("DoH 端点连通性", "未测试")) }
    var webViewEch by remember { mutableStateOf(DiagItem("WebView ECH", "未测试")) }
    var settings by remember { mutableStateOf(DiagItem("当前 DoH 设置", "未测试")) }

    fun log(tag: String, msg: String) = EchTrace.event("ECH诊断[$tag] $msg")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECH 诊断") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onClose) {
                        Text("关闭")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagCard(
                item = conscrypt,
                onTest = {
                    conscrypt = conscrypt.copy(testing = true, status = "测试中…")
                    scope.launch(Dispatchers.IO) {
                        val ok = ConscryptEch.install()
                        val ver = runCatching { Conscrypt.version().toString() }.getOrDefault("?")
                        val msg = "ready=${ConscryptEch.ready} install=$ok version=$ver"
                        log("Conscrypt", msg)
                        withContext(Dispatchers.Main) {
                            conscrypt = DiagItem("Conscrypt ECH", if (ok) "✅ $msg" else "❌ $msg")
                        }
                    }
                }
            )
            DiagCard(
                item = dohResolve,
                onTest = {
                    dohResolve = dohResolve.copy(testing = true, status = "测试中…")
                    scope.launch(Dispatchers.IO) {
                        val t0 = System.currentTimeMillis()
                        val result = runCatching { EchDoh.resolve("hanime1.me") }
                        val ms = System.currentTimeMillis() - t0
                        val msg = result.fold(
                            onSuccess = { addrs ->
                                "耗时 ${ms}ms，${addrs.size} 个 IP: " +
                                    addrs.joinToString { it.hostAddress ?: "?" }
                            },
                            onFailure = { e ->
                                "失败（${ms}ms）：${e.javaClass.simpleName}: ${e.message}"
                            }
                        )
                        log("DoH解析", msg)
                        withContext(Dispatchers.Main) {
                            dohResolve = DiagItem(
                                "EchDoh.resolve(hanime1.me)",
                                if (result.isSuccess && result.getOrDefault(emptyList()).isNotEmpty()) "✅ $msg" else "❌ $msg",
                                msg
                            )
                        }
                    }
                }
            )
            DiagCard(
                item = dohEndpoint,
                onTest = {
                    dohEndpoint = dohEndpoint.copy(testing = true, status = "测试中…")
                    scope.launch(Dispatchers.IO) {
                        val preset = DohConfig.presets.firstOrNull { it.key == Preferences.dohPreset }
                            ?: DohConfig.presets.first()
                        val url = if (Preferences.dohPreset == "custom" && Preferences.dohCustomUrl.isNotBlank())
                            Preferences.dohCustomUrl else preset.url
                        val t0 = System.currentTimeMillis()
                        val result = runCatching {
                            OkHttpClient.Builder()
                                .connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(10, TimeUnit.SECONDS)
                                .build()
                                .newCall(
                                    Request.Builder()
                                        .url("$url?name=example.com&type=A")
                                        .header("Accept", "application/dns-json")
                                        .build()
                                )
                                .execute()
                                .use { resp -> "${resp.code} (${resp.body?.string()?.take(80)})" }
                        }
                        val ms = System.currentTimeMillis() - t0
                        val msg = "端点=$url\n" + result.fold(
                            onSuccess = { "耗时 ${ms}ms，响应: $it" },
                            onFailure = { e -> "失败（${ms}ms）：${e.javaClass.simpleName}: ${e.message}" }
                        )
                        log("DoH端点", msg.replace("\n", " "))
                        withContext(Dispatchers.Main) {
                            dohEndpoint = DiagItem(
                                "DoH 端点连通性",
                                if (result.isSuccess) "✅" else "❌",
                                msg
                            )
                        }
                    }
                }
            )
            DiagCard(
                item = webViewEch,
                onTest = {
                    webViewEch = webViewEch.copy(testing = true, status = "测试中…")
                    scope.launch(Dispatchers.IO) {
                        // co3 架构：无本地端口，检查域名判定 + ECH 引擎就绪
                        val target = HyWebViewHelper.isTargetHost("hanime1.me")
                        val other = HyWebViewHelper.isTargetHost("example.com")
                        val ready = com.yenaly.han1meviewer.logic.network.ech.EchHttp.isReady
                        val msg = "isTarget(hanime1.me)=$target isTarget(example.com)=$other ECH就绪=$ready"
                        log("WebView ECH", msg)
                        withContext(Dispatchers.Main) {
                            webViewEch = DiagItem(
                                "WebView ECH",
                                if (target && !other) "✅ $msg" else "❌ $msg"
                            )
                        }
                    }
                }
            )
            DiagCard(
                item = settings,
                onTest = {
                    val msg = "USE_DOH=${Preferences.useDoH}\n" +
                        "preset=${Preferences.dohPreset}\n" +
                        "customUrl=${Preferences.dohCustomUrl.ifBlank { "(空)" }}\n" +
                        "timeout=${Preferences.dohTimeoutSeconds}s"
                    log("DoH设置", msg.replace("\n", " "))
                    settings = DiagItem("当前 DoH 设置", "ℹ️", msg)
                }
            )

            Spacer(Modifier.height(8.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var remoteLog: Boolean by remember { mutableStateOf(EchLogReporter.enabled) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("远程日志上报", style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.Switch(
                    checked = remoteLog,
                    onCheckedChange = {
                        remoteLog = it
                        EchLogReporter.enabled = it
                        log("远程日志", if (it) "已开启" else "已关闭")
                        android.widget.Toast.makeText(ctx, if (it) "远程日志已开启" else "远程日志已关闭", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
            Text(
                "开启后 ECH 事件实时上报到 log 服务器，开发者可远程查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val text = EchTrace.dump()
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ECH日志", text))
                    android.widget.Toast.makeText(ctx, "ECH 日志已复制（${text.lines().size} 行）", android.widget.Toast.LENGTH_SHORT).show()
                    log("复制日志", "${text.lines().size} 行已复制到剪贴板")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("复制 ECH 日志到剪贴板")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "提示：复现问题后点上面复制，发给开发者分析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagCard(item: DiagItem, onTest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = onTest,
                    enabled = !item.testing,
                ) {
                    Text(if (item.testing) "…" else "测试")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.status, style = MaterialTheme.typography.bodyMedium)
            if (item.detail.isNotBlank() && item.detail != item.status.removePrefix("✅ ").removePrefix("❌ ")) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
