package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 安装 Conscrypt 作为首选 JSSE provider，并提供带 ECH 的 SSLSocketFactory。
 *
 * 必须自带 Conscrypt：`Conscrypt.setEchConfigList` 需要 2.7-alpha 及以上；
 * 系统自带的 TLS 栈在 Android 16 及以下没有 ECH（原生支持始于 API 37）。
 */
object EchProvider {

    private const val TAG = "EchProvider"
    private const val PROVIDER_NAME = "Conscrypt"

    @Volatile
    private var installed = false

    @Volatile
    private var sslContext: SSLContext? = null

    @Volatile
    private var trustManager: X509TrustManager? = null

    /** Conscrypt 是否可用；不可用时业务侧应保持原有 TLS 栈（并对强制域名 fail-closed）。 */
    val isAvailable: Boolean get() = installed

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val result = runCatching {
                if (Security.getProvider(PROVIDER_NAME) == null) {
                    Security.insertProviderAt(Conscrypt.newProvider(), 1)
                }
                val context = SSLContext.getInstance("TLS", PROVIDER_NAME)
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as java.security.KeyStore?)
                val manager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                    ?: error("no X509TrustManager from platform")
                context.init(null, arrayOf(manager), null)
                sslContext = context
                trustManager = manager
            }
            installed = result.isSuccess
            if (result.isSuccess) {
                val version = runCatching {
                    Conscrypt.version().let { "${it.major()}.${it.minor()}.${it.patch()}" }
                }.getOrDefault("unknown")
                Diagnostics.event(
                    "ech_provider_ready",
                    mapOf("provider" to PROVIDER_NAME, "version" to version),
                )
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "Conscrypt install failed", error)
                Diagnostics.event(
                    "ech_provider_failure",
                    mapOf(
                        "error_type" to (error?.javaClass?.simpleName ?: "unknown"),
                        "error" to (error?.message ?: "unknown"),
                    ),
                )
            }
        }
    }

    /** 供 OkHttp 使用的 socket factory；Conscrypt 不可用时返回 null。 */
    fun socketFactory(dohUrlProvider: () -> String?): SSLSocketFactory? {
        install()
        val base = sslContext?.socketFactory ?: return null
        return EchSslSocketFactory(base, dohUrlProvider)
    }

    fun platformTrustManager(): X509TrustManager? {
        install()
        return trustManager
    }
}
