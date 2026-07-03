package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.yenaly_libs.utils.applicationContext
import org.chromium.net.CronetEngine

/**
 * 管理 CronetEngine，用于支持 ECH (Encrypted Client Hello)。
 *
 * ECH 的工作原理：
 * 1. 客户端通过 DoH 查询目标的 HTTPS/SVCB DNS 记录，获取 ECHConfigList（加密公钥）
 * 2. 用该公钥加密 TLS ClientHello 中的敏感部分（包括真实 SNI）
 * 3. 中间人只能看到通往 CDN 前端的加密连接，无法基于 SNI 做精准阻断
 *
 * Cronet（Chrome 同款网络栈）内置 BoringSSL，原生支持 ECH。
 * 运行时由系统自动加载 Cronet provider（通过 Google Play Services）。
 * 如果设备不支持，自动回退到 OkHttp。
 *
 * @project Han1meViewer ECH fork
 */
object CronetManager {

    private const val TAG = "CronetManager"

    @Volatile
    var engine: CronetEngine? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * 初始化 Cronet 引擎。
     * 直接构建 CronetEngine，系统会自动查找可用的 Cronet provider。
     * 如果设备没有可用的 provider，回退到 OkHttp。
     *
     * @param onReady 初始化完成回调（无论成功或失败都会调用）
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (isReady && engine != null) {
            Log.i(TAG, "Cronet already ready")
            onReady?.invoke()
            return
        }

        try {
            Log.i(TAG, "Building Cronet engine with ECH + DoH")
            engine = buildEngine()
            isReady = true
            Log.i(TAG, "Cronet engine ready")
        } catch (e: Exception) {
            Log.e(TAG, "Cronet init failed, falling back to OkHttp: ${e.message}")
            isReady = false
        }
        onReady?.invoke()
    }

    /**
     * 构建 CronetEngine，配置 DoH 和 ECH。
     *
     * ECH 在 Cronet 中是自动的：当 DoH 启用且服务端支持时，
     * BoringSSL 会自动用从 DNS HTTPS 记录获取的 ECHConfig 加密 ClientHello。
     */
    private fun buildEngine(): CronetEngine {
        val dohUrl = DohConfig.resolveUrl() ?: "https://cloudflare-dns.com/dns-query"

        val experimentalOptions = """
            {
              "dns_over_https": {
                "templates": "$dohUrl",
                "enable": true
              }
            }
        """.trimIndent()

        Log.i(TAG, "CronetEngine config: DoH=$dohUrl, ECH=auto")

        val builder = CronetEngine.Builder(applicationContext)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)

        // setExperimentalOptions 可能需要特定 Cronet API 版本，用反射兼容
        try {
            val method = CronetEngine.Builder::class.java
                .getMethod("setExperimentalOptions", String::class.java)
            method.invoke(builder, experimentalOptions)
            Log.i(TAG, "setExperimentalOptions called successfully")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setExperimentalOptions not available: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "setExperimentalOptions failed: ${e.message}")
        }

        return builder.build()
    }

    /**
     * 重建 CronetEngine（当 DoH 设置变更时调用）。
     */
    fun rebuildEngine() {
        if (!isReady) return
        engine = buildEngine()
        Log.i(TAG, "CronetEngine rebuilt")
    }
}
