package com.yenaly.han1meviewer.logic.network.ech

import okhttp3.OkHttpClient

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
    // 唯一保留的拦截器：ECH 被拒时清缓存，让重试拿到 retryConfigs
    .addInterceptor(EchRetryInterceptor())

/** ECH 传输层是否已就绪（设置页/诊断用） */
object EchHttp {
    val isReady: Boolean get() = ConscryptEch.ready

    /** 用户改了 DoH 设置后调用：让解析器与 ECH 缓存跟着刷新 */
    fun onDohSettingsChanged() = EchDoh.invalidateAll()
}
