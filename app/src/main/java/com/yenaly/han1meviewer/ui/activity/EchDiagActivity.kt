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
import com.yenaly.han1meviewer.logic.network.ech.EchTrace
import com.yenaly.han1meviewer.logic.network.ech.MiniProxy
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
    var miniProxy by remember { mutableStateOf(DiagItem("MiniProxy", "未测试")) }
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
                item = miniProxy,
                onTest = {
                    miniProxy = miniProxy.copy(testing = true, status = "测试中…")
                    scope.launch(Dispatchers.IO) {
                        val port = MiniProxy.ensureRunning()
                        val running = MiniProxy.isRunning
                        // 实际连一下本地端口
                        val reachable = runCatching {
                            java.net.Socket().use { s ->
                                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 3000)
                                true
                            }
                        }.getOrDefault(false)
                        val msg = "isRunning=$running port=$port 本地连通=$reachable"
                        log("MiniProxy", msg)
                        withContext(Dispatchers.Main) {
                            miniProxy = DiagItem(
                                "MiniProxy",
                                if (running && reachable) "✅ $msg" else "❌ $msg"
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
            Text(
                "提示：所有结果已写入 EchTrace，adb logcat 搜 HY-ECH-TRACE 可抓。",
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
