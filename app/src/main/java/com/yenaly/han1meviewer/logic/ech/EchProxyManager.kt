package com.yenaly.han1meviewer.logic.ech

import android.content.Context
import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import echproxy.Echproxy
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
 * 代理监听 127.0.0.1:<port>,把请求通过 ECH TLS 握手转发,
 * 隐藏 SNI 防止被 GFW 重置。ECH 公钥配置来自 cloudflare-ech.com(缓存5h),
 * 握手失败自动降级普通 TLS。
 *
 * DoH 端点:显式参数 > 用户本地 DoH 预设 > 内置网关兜底。
 * 远端 DNS TXT 配置已移除(DoH 解析结果由服务端直接控制,客户端只管解析)。
 * DNS 缓存与 DoH 端点绑定,端点一切换自动冲掉旧缓存(防毒 IP 跨端点复活)。
 *
 * 用法:
 *   EchProxyManager.start(context)   // 启动,返回本地代理端口
 *   EchProxyManager.stop()           // 停止
 *   EchProxyManager.isRunning        // 是否在运行
 *   EchProxyManager.proxyUrl(url)    // WebView 登录用的代理内嵌 URL
 */
object EchProxyManager {

    private const val TAG = "EchProxy"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = port > 0

    private var cachePath: String? = null

    /** 内置 DoH 兜底(自有网关,解析结果可控)。 */
    const val DEFAULT_DOH = "https://tgxjjdszvu.cloudflare-gateway.com/dns-query"

    /**
     * 本地边缘 IP 兜底(实测可用的 Cloudflare anycast)。
     * DNS 不可信时(污染/网关策略误伤)直接拨这些 IP,ECH+SNI 认证。
     * 纯本地常量,不走任何远程下发。
     */
    const val EDGE_IP_FALLBACK = "104.26.9.104,104.26.8.104,172.67.74.156,172.64.146.66"

    /** 状态轮询任务。 */
    private var statusJob: kotlinx.coroutines.Job? = null

    /**
     * 启动 ECH 代理。
     * DoH 来源:显式参数 > 用户本地预设 > 内置网关兜底。
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
            Log.i(TAG, "starting ECH proxy on 127.0.0.1:$chosen (doh=$dohArg)")

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
            Log.i(TAG, "ECH proxy started on 127.0.0.1:$chosen")
            startStatusPolling()
            chosen
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy start failed", e)
            port = -1
            -1
        }
    }

    /** 本地 DoH 预设(用户设置),显式参数缺失时的回退。 */
    private fun localPresetDoh(): String? = runCatching {
        when (Preferences.dohPreset) {
            "custom" -> DohConfig.customUrl().takeIf { it.isNotBlank() }
            else -> DohConfig.selectedPreset().url
        }
    }.getOrNull()

    /** 每 3 秒把 ECH 代理的握手/降级状态打到 logcat。 */
    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = scope.launch {
            var last = ""
            while (isRunning) {
                val s = try { Echproxy.lastStatus() } catch (e: Throwable) { "" }
                if (s.isNotBlank() && s != last) {
                    last = s
                    Log.i(TAG, "status: $s")
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    /** 代理内嵌 URL(WebView 登录用): http://127.0.0.1:port/https://host/path。 */
    fun proxyUrl(targetUrl: String): String? {
        val p = port
        if (p <= 0) return null
        val t = if (targetUrl.startsWith("http")) targetUrl else "https://$targetUrl"
        return "http://127.0.0.1:$p/$t"
    }

    /** 停止 ECH 代理。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext
        try {
            Echproxy.stop()
            Log.i(TAG, "ECH proxy stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy stop failed", e)
        } finally {
            port = -1
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

    /** 获取最近的状态摘要(供调试显示)。 */
    fun status(): String = try {
        Echproxy.lastStatus()
    } catch (e: Throwable) {
        "status unavailable: ${e.message}"
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
