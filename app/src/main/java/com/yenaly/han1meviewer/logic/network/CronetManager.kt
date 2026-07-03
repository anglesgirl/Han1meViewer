package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.google.android.gms.net.CronetProviderInstaller
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
 * Cronet (Chrome 同款网络栈) 内置 BoringSSL，原生支持 ECH。
 * 当 DoH 配置启用后，ECH 会自动协商（服务端需支持，Cloudflare 全站默认开启）。
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
     * 初始化 Cronet 引擎。通过 Google Play Services 加载 Cronet 实现，
     * 避免在 APK 中内置 Cronet 二进制文件。
     *
     * @param onReady 初始化完成回调（无论成功或失败都会调用）
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (isReady && engine != null) {
            Log.i(TAG, "Cronet already ready")
            onReady?.invoke()
            return
        }

        CronetProviderInstaller.installProvider(applicationContext)
            .addOnSuccessListener {
                Log.i(TAG, "Cronet provider installed, building engine with ECH + DoH")
                engine = buildEngine()
                isReady = true
                onReady?.invoke()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Cronet provider unavailable, falling back to OkHttp: ${e.message}")
                isReady = false
                onReady?.invoke()
            }
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

        return CronetEngine.Builder(applicationContext)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            .setExperimentalOptions(experimentalOptions)
            .build()
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
