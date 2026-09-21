package com.yenaly.han1meviewer.logic.network.ech

import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response

/**
 * 给 OkHttp 客户端挂上 Conscrypt ECH 传输层。
 *
 * 取代原先「Go 本地代理 + `EchInterceptor` 改写 URL 到 127.0.0.1:port + `HProxySelector`」
 * 那一整套：现在 TLS 层直接由 Conscrypt 承担，OkHttp 的重定向 / Cookie / gzip / HTTP2
 * 全部走原生语义，**不再需要拦截器改 URL、也不再有本地端口**。
 *
 * 用法（在 `OkHttpClient.Builder()` 之后、`build()` 之前调用）：
 * ```
 * OkHttpClient.Builder()
 *     ...
 *     .echTransport(HDns())        // 代替原来的 .addInterceptor(EchInterceptor())
 *     .build()                     // 和 .proxySelector(HProxySelector())
 * ```
 *
 * ⚠️ 同时**要删掉两样东西**（否则会互相打架）：
 *   - `.addInterceptor(EchInterceptor())` —— 它把请求改写成 127.0.0.1，ECH 注入就失效了
 *   - `.proxySelector(HProxySelector())` —— 本地端口已不存在，留着会把请求指向死地址
 *   - 受保护域名客户端上的 `.cookieJar(CookieJar.NO_COOKIES)` —— 那是为"手工注入 Cookie"
 *     准备的；现在域名不再被改写，应当用回 [com.yenaly.han1meviewer.logic.network.HCookieJar]
 */
fun OkHttpClient.Builder.echTransport(
    fallbackDns: okhttp3.Dns = okhttp3.Dns.SYSTEM,
): OkHttpClient.Builder = this
    // 触发 Conscrypt 初始化（惰性；失败时不抛异常，本次请求会 fail-closed）
    .apply { ConscryptEch.install() }
    .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
    // 受保护域名走 DoH（拿不到就抛异常，不回落系统 DNS）；其余域名保持原策略
    .dns(EchDns(fallbackDns))
    // ① H3（QUIC+ECH）优先：只接管无状态静态资源 GET。
    //    H3 同样带 ECH（配置由 HyEchH3 一并传给 QUIC），所以它不是"退回明文"，
    //    而是走 UDP/443 —— 掐 TCP SNI 的网络未必连 QUIC 一起掐。
    //    失败会记 24h 负缓存并立刻回落下面的 TCP 链路，用户无感。
    .addInterceptor(H3Interceptor())
    // ② ③ TCP+ECH；ECH 被服务器拒绝时：普通域名标记后转明文重试，
    //     核心域名清 ECH 缓存用新配置重试（不降级，明文必被 RST）
    .addInterceptor(EchRetryInterceptor())

/** 共享的 ECH OkHttp 客户端 */
object EchHttp {

    val isReady: Boolean get() = ConscryptEch.ready

    /** 用户改了 DoH 设置后调用：让解析器与 ECH 缓存跟着刷新 */
    fun onDohSettingsChanged() = EchDoh.invalidateAll()

    /**
     * 登录 POST 专用客户端。
     *
     * ⚠️ **必须 followRedirects(false)**：登录响应是个 302，凭据挂在它的 `Set-Cookie` 上。
     * 一旦自动跟随重定向，就只能看到最终的 200，读不到那个 Set-Cookie ——
     * 表现为"登录提交成功但登录态没同步回来"。这是老实现栽过的坑。
     */
    val loginClient: OkHttpClient by lazy {
        ConscryptEch.install()
        OkHttpClient.Builder()
            .sslSocketFactory(ConscryptEch.socketFactory, ConscryptEch.trustManager)
            .dns(EchDns())
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)   // Cookie 由 CookieManager 统一管
            .followRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
