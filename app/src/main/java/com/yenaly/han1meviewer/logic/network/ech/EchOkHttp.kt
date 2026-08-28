package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.logic.network.DohConfig
import okhttp3.OkHttpClient

/**
 * 把 ECH 能力挂到 OkHttp 客户端上的统一入口。
 *
 * ECHConfigList 只从应用专属 DoH 获取；DoH 停用即整体失效（fail-closed 由
 * [EchSslSocketFactory] 与 HDns 各自保证）。
 */
object EchOkHttp {

    /**
     * 为 builder 配置带 ECH 的 TLS 栈。Conscrypt 不可用时保持 builder 原样，
     * 此时对强制 ECH 的域名仍会在 socket 层拒绝连接。
     */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val factory = EchProvider.socketFactory { DohConfig.resolveUrl() } ?: return builder
        val trustManager = EchProvider.platformTrustManager() ?: return builder
        return builder.sslSocketFactory(factory, trustManager)
    }
}
