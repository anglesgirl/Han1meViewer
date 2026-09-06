package io.github.daisukikaffuchino.han1meviewer.logic.ech

import android.content.Context
import android.util.Log
import echproxy.Echproxy
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.utils.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket

/**
 * ECH 代理管理器:负责启动/停止 Go ECH 代理(gomobile 编译的 echproxy AAR)。
 *
 * 代理监听 127.0.0.1:<port>,把 Hanime.tv 的请求通过 ECH TLS 握手转发,
 * 隐藏 SNI 防止被 GFW 重置。ECH 公钥配置来自 cloudflare-ech.com(缓存5h),
 * 握手失败自动兜底一次,再失败降级普通 TLS。
 *
 * DoH 端点:显式参数 > 用户本地 DoH 预设 > 内置 alidns 兜底。
 * 远端 DNS TXT 配置已移除(DoH 解析结果由服务端直接控制,客户端只管解析)。
 *
 * 用法:
 *   EchProxyManager.start(context)   // 启动,返回本地代理端口
 *   EchProxyManager.stop()           // 停止
 *   EchProxyManager.isRunning        // 是否在运行
 */
object EchProxyManager {

    private const val TAG = "EchProxy"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = port > 0

    private var cachePath: String? = null

    /** 兜底 DoH 端点(本地预设不可用时)。 */
    const val DEFAULT_DOH = "https://dns.alidns.com/dns-query"

    /**
     * 本地边缘 IP 兜底(实测可用的 Cloudflare anycast)。
     * DNS 不可信时(污染/网关策略误伤)直接连这些 IP,ECH+SNI 认证。
     * 纯本地常量,不走任何远程下发。
     */
    const val EDGE_IP_FALLBACK = "104.26.9.104,104.26.8.104,172.67.74.156,172.64.146.66"

    /** 状态轮询任务。 */
    private var statusJob: kotlinx.coroutines.Job? = null

    /**
     * 启动 ECH 代理。
     * DoH 来源:显式参数 > 用户本地预设 > 内置 alidns 兜底。
     * @param context 用于定位缓存目录
     * @param doh 显式指定 DoH(调试用,可空则走本地预设)
     * @return 本地代理端口,失败返回 -1
     */
    suspend fun start(context: Context, doh: String? = null): Int = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext port
        try {
            cachePath = File(context.filesDir, "ech-public-config.json").absolutePath
            val chosen = freePort()

            val dohArg = doh
                ?: localPresetDoh()
                ?: DEFAULT_DOH
            LogUtil.record(
                "I", TAG,
                "starting ECH proxy on 127.0.0.1:$chosen (doh=$dohArg)"
            )

            Echproxy.start(
                "127.0.0.1:$chosen",          // listen
                "hanime.tv",                  // target
                "",                           // echB64 (空 → DoH/cloudflare-ech.com + fallback)
                dohArg,                       // DoH endpoint
                EDGE_IP_FALLBACK,             // ipList (本地边缘 IP,优先直拨)
                cachePath!!,                  // ECH 公钥配置缓存(5h)
                false,                        // insecure
            )
            port = chosen
            LogUtil.record("I", TAG, "ECH proxy started on 127.0.0.1:$chosen")
            // 让系统代理(HttpURLConnection/WebView)指向本地 ECH 代理。
            HProxySelector.rebuildNetwork()
            startStatusPolling()
            chosen
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy start failed", e)
            LogUtil.record("E", TAG, "ECH proxy start failed: ${e.message}")
            port = -1
            -1
        }
    }
    /** 本地 DoH 预设(用户设置),显式参数缺失时的回退。 */
    private fun localPresetDoh(): String? = runCatching {
        val cfg = io.github.daisukikaffuchino.han1meviewer.logic.network.DohConfig
        when (SettingsRepository.dohPreset) {
            "custom" -> cfg.customUrl().takeIf { it.isNotBlank() }
            else -> cfg.selectedPreset().url
        }
    }.getOrNull()

    /** 每 3 秒把 ECH 代理的握手/降级状态写入日志缓冲(日志页可见)。 */
    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = scope.launch {
            var last = ""
            while (isRunning) {
                val s = try { Echproxy.lastStatus() } catch (e: Throwable) { "" }
                if (s.isNotBlank() && s != last) {
                    last = s
                    LogUtil.record("I", TAG, "status: $s")
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    /** 停止 ECH 代理。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext
        try {
            Echproxy.stop()
            Log.i(TAG, "ECH proxy stopped")
            LogUtil.record("I", TAG, "ECH proxy stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy stop failed", e)
            LogUtil.record("E", TAG, "ECH proxy stop failed: ${e.message}")
        } finally {
            port = -1
            // 恢复系统代理到用户原配置。
            io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector.rebuildNetwork()
        }
    }

    /** 启动(非挂起版本,供 Application 使用)。 */
    fun startAsync(context: Context, doh: String? = null) {
        scope.launch { start(context, doh) }
    }

    /** 停止(非挂起版本)。 */
    fun stopAsync() {
        scope.launch { stop() }
    }

    /** 获取最近的状态摘要(供调试页/设置页显示)。 */
    fun status(): String = try {
        Echproxy.lastStatus()
    } catch (e: Throwable) {
        "status unavailable: ${e.message}"
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
