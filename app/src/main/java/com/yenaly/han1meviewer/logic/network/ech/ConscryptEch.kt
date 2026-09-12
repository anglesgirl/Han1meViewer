package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Conscrypt（BoringSSL 内核）承担的 ECH 传输层 —— 取代原先的 Go 本地代理。
 *
 * **为什么换**：Go 那套是「外挂线程 + 本地端口」（`EchProxyManager` + `HProxySelector`），
 * 进程外的东西在系统内存紧张时会被 kill，表现为代理莫名卡死/断连。
 * Conscrypt 是标准 JSSE provider，in-process，并且 OkHttp 的重定向 / Cookie / gzip /
 * 连接池全部走原生语义 —— 不再需要「拦截器改写 URL → 127.0.0.1:port」这一层。
 *
 * 【最容易踩的坑，必须保留注释】Conscrypt 用**反射**从 X509TrustManager 上取
 * `getNetworkSecurityPolicy()`；取不到就回落平台默认策略（Android API 36 = DISABLED），
 * 而 DISABLED 会让 `getEchOptions()` 返回 null、`enableEchBasedOnPolicy()` 首行就 return
 * —— 结果是 setEchConfigList 完全白设，ECH 扩展**一个字节都不发**（且完全静默，
 * 日志上一切"成功"）。所以 [PolicyTrustManager.getNetworkSecurityPolicy] 那个方法
 * 就是整套 ECH 的总开关，**绝不能删、绝不能改成 private**。
 *
 * 字节码依据（conscrypt-android:2.7.0 正式版，已核对）：
 * - `ConscryptNetworkSecurityPolicy.getDomainEncryptionMode()` 直接 `return UNKNOWN`（硬编码）
 * - `SSLParametersImpl.getNetworkSecurityPolicyMethod(tm)` 做的是
 *   `tm.getClass().getMethod("getNetworkSecurityPolicy")` —— 无类型白名单、只找 public 无参
 * ⟹ 结论：换任何自定义 TrustManager 都行，但**升级 Conscrypt 版本并不能替代这个方法**。
 */
object ConscryptEch {

    private const val TAG = "HY-ECH"

    @Volatile
    var ready = false
        private set

    /**
     * 惰性：`Conscrypt.newProvider()` 会触发 native 库加载。
     * 放在启动早期的 `Application.onCreate` 里执行有风险（那时其它 native 初始化可能还没就绪），
     * 所以改成"第一次真正发请求时"才初始化 —— 失败也只是那次请求 fail-closed，不拖垮启动。
     */
    private val provider: java.security.Provider by lazy { Conscrypt.newProvider() }

    private val systemTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("系统 X509TrustManager 不可用")
    }

    /** 给 OkHttp 用的信任管理器（证书校验委托给系统，另挂 ECH 策略） */
    val trustManager: X509TrustManager by lazy { PolicyTrustManager(systemTrustManager) }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3", provider).apply {
            // 必须把 PolicyTrustManager 传进 SSLContext，Conscrypt 才反射得到策略
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }

    val socketFactory: SSLSocketFactory by lazy { EchSocketFactory(sslContext.socketFactory) }

    /**
     * 幂等安装：触发惰性初始化并确认真的可用。
     * **绝不抛异常**（启动路径可能调到它），失败返回 false。
     */
    fun install(): Boolean {
        if (ready) return true
        return runCatching {
            provider
            sslContext
            socketFactory
            ready = true
            Log.i(TAG, "Conscrypt ECH 就绪，version=${Conscrypt.version()}")
            true
        }.getOrElse { t ->
            Log.e(TAG, "Conscrypt ECH 初始化失败: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    class PolicyTrustManager(private val delegate: X509TrustManager) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        /**
         * Conscrypt 反射找的就是这个方法 —— 整套 ECH 的开关。
         * ⚠️ 必须 public、必须无参（`Class.getMethod` 只找 public 方法）。
         */
        @Suppress("unused")
        fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = POLICY
    }

    private val POLICY = object : NetworkSecurityPolicy {
        override fun isCertificateTransparencyVerificationRequired(hostname: String?): Boolean = false

        override fun getCertificateTransparencyVerificationReason(hostname: String?):
            CertificateTransparencyVerificationReason =
            CertificateTransparencyVerificationReason.UNKNOWN

        override fun getDomainEncryptionMode(hostname: String?): DomainEncryptionMode =
            if (hostname != null && EchHosts.isProtected(hostname)) DomainEncryptionMode.ENABLED
            else DomainEncryptionMode.DISABLED
    }

    /**
     * 包装 Conscrypt 的 SSLSocketFactory：返回 socket 前按 host 注入 ECHConfigList。
     * OkHttp 走的是 `createSocket(Socket, String, int, boolean)` 重载。
     *
     * 拿不到配置时**抛异常**（fail-closed）—— 宁可不连，也绝不明文暴露被墙域名的 SNI。
     */
    private class EchSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun prepare(s: Socket, host: String?): Socket {
            if (host == null || s !is SSLSocket || !EchHosts.isProtected(host)) return s
            val cfg = EchDoh.echConfigList(host)
                ?: throw IOException("ECH 配置不可用（fail-closed）：拒绝以明文访问 $host")
            try {
                Conscrypt.setEchConfigList(s, cfg)
            } catch (t: Throwable) {
                throw IOException("setEchConfigList 失败（fail-closed）: ${t.message}")
            }
            return s
        }

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            prepare(delegate.createSocket(s, host, port, autoClose), host)

        override fun createSocket(host: String, port: Int): Socket =
            prepare(delegate.createSocket(host, port), host)

        override fun createSocket(
            host: String, port: Int, localHost: InetAddress, localPort: Int,
        ): Socket = prepare(delegate.createSocket(host, port, localHost, localPort), host)

        // 只给到 IP、拿不到域名的那两个重载无法注入 ECH；
        // 受保护域名不会走到这里（有 EchDns 与带 host 的重载兜着）
        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port)

        override fun createSocket(
            address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int,
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
    }
}

/**
 * ECH 被服务器拒绝（密钥轮换 / 配置过期）时清掉缓存，让 OkHttp 的重试拿到新配置。
 * Conscrypt 会抛带 EchRejected 的异常并附 retryConfigs，这里**只做失效 + 重试**，
 * 不改写任何传输语义 —— 这是本方案留下的唯一一个拦截器。
 */
class EchRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        return try {
            chain.proceed(chain.request())
        } catch (t: Throwable) {
            val echRejected = generateSequence(t) { it.cause }
                .any { it.javaClass.simpleName.contains("EchRejected", ignoreCase = true) }
            if (echRejected && EchHosts.isProtected(host)) {
                Log.w("HY-ECH", "ECH 被拒，清缓存以便用 retryConfigs 重试: $host")
                EchDoh.invalidateEch(host)
            }
            throw t
        }
    }
}
