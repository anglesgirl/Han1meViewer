package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.util.EchStats
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File

/**
 * 把 **H3（QUIC + ECH）**接进 OkHttp 链路 —— 此前 H3 只服务 WebView 的
 * `shouldInterceptRequest`，Coil 图片 / ExoPlayer 这些走 OkHttp 的链路完全用不上它。
 *
 * 用户定调的完整优先级（「能走 ECH 就走 ECH，走不了降级；明文的部分可以试 H3」）：
 *
 * ```
 * ① H3（QUIC + ECH）        ← 本拦截器负责，**带 ECH**，UDP/443
 * ② TCP + ECH（Conscrypt）  ← OkHttp 原生路径（EchSocketFactory 注入）
 * ③ 明文 TCP                ← 仅普通域名；核心域名仍然是 fail-closed
 * ```
 *
 * H3 排在最前是有实测依据的：网络对 UDP 的策略跟 TCP 不是一回事 —— 掐 TCP SNI 的网络里
 * QUIC 未必一起死。而 ECH 配置本来就由 [HyEchH3] 一并传给 QUIC，所以走 H3 并不是
 * "退回明文"，它同样隐藏 SNI。
 *
 * 只接管**无状态静态资源**（图片/样式/脚本/字体）的 GET，原因见 [HyEchH3.isStaticAsset]：
 * 这条通路不发 Cookie、也不回响应头，拿它跑登录/接口会把会话读坏。
 * 任何失败都返回 `chain.proceed()` —— 交回下面的 ②③ 链路，用户无感。
 */
class H3Interceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 只接管 GET + 静态扩展名：有状态请求（POST / Cookie / HTML 文档）走 TCP+ECH
        if (request.method != "GET" || !HyEchH3.isStaticAsset(url)) return chain.proceed(request)

        // 负缓存（`ech_h3_state`，24h）拦下的域名直接放过，不再白试一次 QUIC 握手
        if (!HyEchH3.shouldTryH3(url.host)) return chain.proceed(request)

        val file = runCatching { HyEchH3.fetchResourceToFile(url.toString()) }.getOrNull()
        if (file == null || !file.exists() || file.length() == 0L) {
            // [HyEchH3] 内部已记负缓存；这里直接回落 ②③
            return chain.proceed(request)
        }

        EchTrace.event("H3 命中 ${url.host} ${file.length()}B ${url.encodedPath}")
        runCatching {
            EchStats.event("h3_hit", mapOf("host" to url.host, "len" to file.length().toString()))
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_3)
            .code(200)
            .message("OK (H3+ECH)")
            .body(FileBody(file, HyEchH3.mimeFor(url).toMediaTypeOrNull()))
            .build()
    }

    /**
     * H3 的结果落在临时文件里（native 侧只写文件），所以包一个用完即删的 body ——
     * 否则 cacheDir 会被图片一点点堆满。
     */
    private class FileBody(
        private val file: File,
        private val mediaType: MediaType?,
    ) : ResponseBody() {

        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = file.length()

        override fun source(): BufferedSource = file.source().buffer()

        override fun close() {
            super.close()
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val TAG = "HY-ECH-H3"
    }
}
