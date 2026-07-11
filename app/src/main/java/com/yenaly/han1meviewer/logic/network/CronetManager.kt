package com.yenaly.han1meviewer.logic.network

import android.util.Log
import org.chromium.net.CronetEngine

/**
 * 管理 CronetEngine，用于支持 ECH (Encrypted Client Hello)。
 *
 * 关键设计：使用 cronet-embedded（原生库直接打包进 APK），
 * 不依赖 Google Play Services，同步初始化，不会弹出任何对话框。
 *
 * ECH 工作原理：
 * 1. Cronet 通过 DoH 查询目标域名的 HTTPS/SVCB DNS 记录，获取 ECHConfigList
 * 2. BoringSSL 用该公钥加密 TLS ClientHello 中的 SNI
 * 3. 中间人只能看到通往 CDN 前端的加密连接，无法基于 SNI 阻断
 *
 * @project Han1meViewer ECH
 */
object CronetManager {

    private const val TAG = "CronetManager"

    /**
     * 用户自定义的 Cloudflare DoH 端点，支持 ECH。
     */
    private const val DEFAULT_DOH_URL = "https://xzam891f5d.cloudflare-gateway.com/dns-query"

    @Volatile
    var engine: CronetEngine? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * 同步初始化 Cronet 引擎。
     * cronet-embedded 直接从 APK 加载原生库，无需 GMS，无异步回调。
     * 必须在构建任何 OkHttp 客户端之前调用。
     */
    fun init() {
        if (isReady && engine != null) {
            Log.i(TAG, "Cronet already ready")
            return
        }

        try {
            engine = buildEngine()
            isReady = true
            Log.i(TAG, "Cronet engine ready (embedded, no GMS required)")
        } catch (e: Exception) {
            Log.e(TAG, "Cronet engine init failed: ${e.message}", e)
            isReady = false
        }
    }

    /**
     * 构建 CronetEngine，配置 DoH。
     * ECH 在 Cronet 中是自动的：DoH 启用且服务端支持时，
     * BoringSSL 自动用 DNS HTTPS 记录中的 ECHConfig 加密 ClientHello。
     */
    private fun buildEngine(): CronetEngine {
        val dohUrl = DEFAULT_DOH_URL

        val experimentalOptions = """
            {
              "dns_over_https": {
                "templates": "$dohUrl",
                "enable": true
              }
            }
        """.trimIndent()

        Log.i(TAG, "CronetEngine config: DoH=$dohUrl, ECH=auto")

        val builder = CronetEngine.Builder(com.yenaly.yenaly_libs.utils.applicationContext)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)

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
