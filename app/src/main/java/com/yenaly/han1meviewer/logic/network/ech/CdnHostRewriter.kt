package com.yenaly.han1meviewer.logic.network.ech

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把被**按域名封锁**的 CDN 请求改写到同一台 CDN 的**备用域名**上。
 *
 * 背景（实测结论）：中国移动按 SNI **单独封锁**了 `vdownload.hembed.com`（图片 CDN），
 * 其他运营商不受影响。而这个域名的后端是 CDN77：
 *
 * ```
 * vdownload.hembed.com  --CNAME-->  1497203185.rsc.cdn77.org
 * ```
 *
 * 两个域名**指向同一组边缘节点、共用同一张证书**
 * （实测证书 SAN = `1497203185.rsc.cdn77.org, vdownload.hembed.com`），
 * 且 CDN77 的 `?secure=` 防盗链签名**不绑定 Host**
 * （实测同一签名在两个域名上均返回 200，content-length 完全一致）。
 *
 * 所以只要把 Host 换成备用域名，SNI 就不再是移动封的那个，请求照常成功：
 * **不需要自建反代、不产生额外带宽成本**。
 *
 * ⚠️ 只改写**已知有备用域名**的 host。像 `vdownload-8.hembed.com`（视频，站方自建 VPS）
 * 证书里只有它自己，改了会直接证书错误 —— **绝不能进这张表**。
 *
 * 改写失败（备用域名哪天变了、证书不再覆盖等）时不做任何兜底重试：
 * 让请求如实失败并留下 `EchTrace` 记录，避免"悄悄退回被封域名"这种更难查的行为。
 */
class CdnHostRewriter : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        val alt = ALTERNATE_HOSTS[host] ?: return chain.proceed(request)

        val rewritten = request.newBuilder()
            .url(request.url.newBuilder().host(alt).build())
            .build()

        EchTrace.event("CDN Host 改写（绕按域名封锁）: $host -> $alt${request.url.encodedPath}")
        return chain.proceed(rewritten)
    }

    private companion object {

        /**
         * 被封锁 host → 同一 CDN 的备用 host。
         *
         * 加新条目之前必须实测三件事（缺一不可）：
         *   1. 备用域名与原域名解析到**同一后端**；
         *   2. 备用域名的**证书覆盖原域名**（SAN 含两者）—— 否则 TLS 校验失败；
         *   3. 原域名的**鉴权参数不绑定 Host**（同一签名在两个域名上都返回 200）。
         */
        val ALTERNATE_HOSTS = mapOf(
            // CDN77：移动按 SNI 封锁了它；rsc 域名同一张证书、签名不绑 Host（均已实测）
            "vdownload.hembed.com" to "1497203185.rsc.cdn77.org",
        )
    }
}
