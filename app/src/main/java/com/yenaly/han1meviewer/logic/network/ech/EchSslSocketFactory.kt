package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 在 TLS 握手前把 ECHConfigList 注入 Conscrypt socket，使真实域名以加密形式出现在
 * ClientHelloInner，网络中间设备只能看到 ECHConfig 里的 public_name（如 cloudflare-ech.com）。
 *
 * 之所以必须自带 Conscrypt：系统原生 ECH 从 Android 17（API 37）才有，本机 Android 16 没有。
 *
 * 失败策略遵循 fail-closed：列入 [EchPolicy.requiresEch] 的域名若拿不到 ECHConfig，
 * 直接拒绝建立连接，绝不退化成明文 SNI 直连。
 */
class EchSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val dohUrlProvider: () -> String?,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        applyEch(delegate.createSocket(socket, host, port, autoClose), host)

    override fun createSocket(host: String, port: Int): Socket =
        applyEch(delegate.createSocket(host, port), host)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = applyEch(delegate.createSocket(host, port, localHost, localPort), host)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        applyEch(delegate.createSocket(host, port), host.hostName)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = applyEch(delegate.createSocket(address, port, localAddress, localPort), address.hostName)

    private fun applyEch(socket: Socket, host: String?): Socket {
        if (socket !is SSLSocket || host.isNullOrBlank()) return socket
        val mustEncrypt = EchPolicy.requiresEch(host)

        val dohUrl = dohUrlProvider()
        if (dohUrl.isNullOrBlank()) {
            // 没有可信 DoH 就无法安全获取 ECHConfig。
            if (mustEncrypt) {
                socket.closeQuietly()
                throw IOException("ECH required for $host but no DoH endpoint configured (fail-closed)")
            }
            return socket
        }

        val config = EchConfigResolver.configFor(dohUrl, host)
        if (config == null || config.isEmpty()) {
            if (mustEncrypt) {
                Diagnostics.event(
                    "ech_refused",
                    mapOf("host" to host, "reason" to "no_config", "fail_closed" to true),
                )
                socket.closeQuietly()
                throw IOException("ECH required for $host but no ECHConfig available (fail-closed)")
            }
            return socket
        }

        val applied = runCatching { Conscrypt.setEchConfigList(socket, config) }.isSuccess
        if (!applied) {
            // Conscrypt 未生效（provider 缺失或非 Conscrypt socket）时不能假装安全。
            if (mustEncrypt) {
                Diagnostics.event(
                    "ech_refused",
                    mapOf("host" to host, "reason" to "conscrypt_unavailable", "fail_closed" to true),
                )
                socket.closeQuietly()
                throw IOException("ECH required for $host but Conscrypt ECH is unavailable (fail-closed)")
            }
            Log.w(TAG, "Conscrypt.setEchConfigList failed for $host")
            return socket
        }

        Diagnostics.event(
            "ech_applied",
            mapOf("host" to host, "config_len" to config.size, "required" to mustEncrypt),
        )
        return socket
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val TAG = "EchSocketFactory"
    }
}
