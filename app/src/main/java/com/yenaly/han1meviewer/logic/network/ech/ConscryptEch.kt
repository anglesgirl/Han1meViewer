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
import java.util.concurrent.ConcurrentHashMap
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
            EchTrace.event("Conscrypt ECH 就绪，version=${Conscrypt.version()}")
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
     * 已经确认「ECH 走不通」的域名：服务器回了 ECH_REJECTED（或拿不到配置）。
     *
     * 实测依据（BoringSSL/OpenSSL 层，本机复现）：给**非 Cloudflare** 域名注入
     * cloudflare-ech.com 的 ECHConfigList 时，握手直接以
     * `error:1000013f:SSL routines:OPENSSL_internal:ECH_REJECTED` 失败，
     * 且 **retry_len=0** —— 服务器**不会**回传 retry_configs。
     * 这就意味着 Conscrypt 没有任何自动降级的机会，"降级"必须由我们自己记。
     *
     * 记下来之后，同一域名不再注入 ECH，直接明文：省掉每次访问都要白白失败一次的握手。
     */
    private val echUnavailable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 见 [echUnavailable]。由 [EchRetryInterceptor] 在识别到 ECH_REJECTED 后调用。 */
    fun markEchUnavailable(host: String) {
        if (echUnavailable.add(host.lowercase())) {
            EchTrace.event("ECH 走不通，该域名转明文（降级）: $host")
        }
    }

    /** 仅供诊断日志：当前已降级为明文的域名快照。 */
    fun echUnavailableHosts(): List<String> = echUnavailable.toList().sorted()

    /**
     * 包装 Conscrypt 的 SSLSocketFactory：返回 socket 前按 host 注入 ECHConfigList。
     * OkHttp 走的是 `createSocket(Socket, String, int, boolean)` 重载。
     *
     * 降级策略（用户定调：**所有网络都先试 ECH，匹配失败降级，能走 ECH 就走 ECH**）：
     * - **核心域名**（[EchHosts.isCoreDomain]）：拿不到配置一律抛异常（fail-closed）。
     *   它们明确被墙，明文 = 把 SNI 写在脸上 = 立刻被 RST —— 降级对它们
     *   既救不了可用性，又输掉安全性，所以宁可不连。
     * - **其他域名**：ECH 不可用就明文放行。它们本来就在明文访问，
     *   少一层加密不会更糟：降级只损失隐私增益，不损失可用性。
     */
    private class EchSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun prepare(s: Socket, host: String?): Socket {
            if (host == null || s !is SSLSocket || !EchHosts.shouldTryEch(host)) return s
            val core = EchHosts.isCoreDomain(host)

            // 已确认走不通的域名：普通域名直接明文；核心域名仍然 fail-closed
            if (echUnavailable.contains(host.lowercase())) {
                if (core) throw IOException("ECH 配置不可用（fail-closed）：拒绝以明文访问 $host")
                return s
            }

            val cfg = EchDoh.echConfigList(host)
            if (cfg == null) {
                if (core) throw IOException("ECH 配置不可用（fail-closed）：拒绝以明文访问 $host")
                EchTrace.event("拿不到 ECH 配置，该域名转明文（降级）: $host")
                markEchUnavailable(host)
                return s
            }

            try {
                Conscrypt.setEchConfigList(s, cfg)
                // ⚠️ conscrypt-android 2.7.0 **没有** getEchConfigList（javap 实证：只有
                // setEchConfigList 的两个重载），所以无法回读校验 —— 别指望在这里"验货"。
                // ECH 到底有没有真的发出去，只能靠两条外部证据：
                //   1) **核心域名能连上** —— 它们明文必被 RST，能通即证明 ECH 扩展真的发出去了；
                //   2) **服务器回 ECH_REJECTED** —— 走不通的信号，由 EchRetryInterceptor 接住。
                // 若哪天出现"核心域名连不上、且没有 ECH_REJECTED"，那就是 PolicyTrustManager
                // 被静默失效（R8 改名 / 未传进 SSLContext），优先查 proguard-rules 的 keep。
                EchTrace.event("ECH 已注入 host=$host cfg=${cfg.size}B 核心=$core")
            } catch (t: Throwable) {
                if (core) throw IOException("setEchConfigList 失败（fail-closed）: ${t.message}")
                EchTrace.event("setEchConfigList 失败，该域名转明文（降级）: $host ${t.message}")
                markEchUnavailable(host)
                return s
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
 * ECH 被拒时的降级/重试。这是本方案唯一保留的拦截器。
 *
 * 两种情况要分开处理（实测 BoringSSL 在服务器不认 ECH 时回 `ECH_REJECTED`，
 * 且 **retry_len=0**、不给 retry_configs，所以 Conscrypt 自己不会降级）：
 *
 * - **核心域名**（被墙站点）：清掉 ECH 缓存、用新配置重试一次。**不降级** ——
 *   明文访问它们等于当场被 RST。通常是密钥轮换/配置过期，换新配置多半就好了。
 * - **普通域名**：把该域名标记为"ECH 走不通"，然后**明文重试一次**。
 *   用户看到的就是一次正常的请求（多花一个 RTT），之后同域名直接明文。
 */
class EchRetryInterceptor : Interceptor {

    private val tag = "HY-ECH"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        return try {
            chain.proceed(request)
        } catch (t: Throwable) {
            if (!isEchRejected(t)) throw t

            if (EchHosts.isCoreDomain(host)) {
                EchTrace.event("ECH 被拒（核心域名），清缓存用新配置重试: $host")
                EchDoh.invalidateEch(host)
                return chain.proceed(request)
            }

            EchTrace.event("ECH 被拒（普通域名），标记后转明文重试: $host")
            ConscryptEch.markEchUnavailable(host)
            return chain.proceed(request)
        }
    }

    /**
     * ECH 被拒的判定：异常链里带 `EchRejected` 类名，
     * 或消息里带 BoringSSL 的 `ECH_REJECTED` 文案。
     */
    private fun isEchRejected(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { cause ->
            cause.javaClass.simpleName.contains("EchRejected", ignoreCase = true) ||
                cause.message?.contains("ECH_REJECTED", ignoreCase = true) == true
        }
}
