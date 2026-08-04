package io.github.daisukikaffuchino.han1meviewer.logic.ech

import android.content.Context
import android.util.Log
import echproxy.Echproxy
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

    /** 兜底 DoH 端点(仅当用户 DoH 未配置时使用)。 */
    const val DEFAULT_DOH = "https://dns.alidns.com/dns-query"

    /** 状态轮询任务。 */
    private var statusJob: kotlinx.coroutines.Job? = null

    /**
     * 启动 ECH 代理。
     * @param context 用于定位缓存目录
     * @param doh DoH JSON 端点(可空,默认用用户配置的 DoH,未配置则 alidns)
     * @return 本地代理端口,失败返回 -1
     */
    suspend fun start(context: Context, doh: String? = null): Int = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext port
        try {
            cachePath = File(context.filesDir, "ech-public-config.json").absolutePath
            val chosen = freePort()
            // 优先用用户配置的 DoH(网络设置里可选 alidns/dnspod/cloudflare),
            // Cloudflare Gateway 在部分网络被墙,会导致代理解析失败 → 全网不通。
            val dohArg = doh ?: runCatching {
                io.github.daisukikaffuchino.han1meviewer.logic.network.DohConfig.resolveUrl()
            }.getOrNull() ?: DEFAULT_DOH
            Log.i(TAG, "starting ECH proxy on 127.0.0.1:$chosen (doh=$dohArg)")
            LogUtil.record("I", TAG, "starting ECH proxy on 127.0.0.1:$chosen (doh=$dohArg)")

            Echproxy.start(
                "127.0.0.1:$chosen",          // listen
                "hanime.tv",                  // target
                "",                           // echB64 (空 → DoH/cloudflare-ech.com + fallback)
                dohArg,                       // DoH JSON endpoint
                "",                           // ipList (可选自定义边缘IP)
                cachePath!!,                  // ECH 公钥配置缓存(5h)
                false,                        // insecure
            )
            port = chosen
            Log.i(TAG, "ECH proxy started on port $chosen")
            LogUtil.record("I", TAG, "ECH proxy started on 127.0.0.1:$chosen")
            // 让系统代理(HttpURLConnection/WebView)指向本地 ECH 代理。
            io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector.rebuildNetwork()
            startStatusPolling()
            chosen
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy start failed", e)
            LogUtil.record("E", TAG, "ECH proxy start failed: ${e.message}")
            port = -1
            -1
        }
    }

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
