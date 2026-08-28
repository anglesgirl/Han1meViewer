package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.diagnostics.Diagnostics
import okhttp3.Interceptor
import okhttp3.Response
import java.security.cert.X509Certificate

/**
 * 握手后验证 ECH 是否真的生效，避免"以为在用 ECH"的自欺欺人。
 *
 * 判据基于 RFC 9849 的握手语义，不依赖 Conscrypt 内部 API：
 * - ECH 被接受时，服务端用**内层**真实域名的证书完成握手 → 证书能匹配真实 host。
 * - ECH 被拒绝时，BoringSSL 会用 ECHConfig 里的 public_name 继续握手并最终抛
 *   ECH rejected；若观察到证书只匹配 public_name（如 cloudflare-ech.com），
 *   说明真实域名并未受保护。
 */
class EchVerificationInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.url.isHttps) return response

        val host = request.url.host
        val required = EchPolicy.requiresEch(host)
        val leaf = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
        val subject = leaf?.subjectX500Principal?.name?.take(120) ?: "unknown"
        val matchesRealHost = leaf?.let { certMatchesHost(it, host) }

        Diagnostics.event(
            "ech_handshake",
            mapOf(
                "host" to host,
                "required" to required,
                "tls" to (response.handshake?.tlsVersion?.javaName ?: "unknown"),
                "cert_matches_host" to (matchesRealHost?.toString() ?: "unknown"),
                "cert_subject" to subject,
            ),
        )
        return response
    }

    /** 证书是否覆盖真实域名（CN 或 SAN dNSName，支持单层通配）。 */
    private fun certMatchesHost(cert: X509Certificate, host: String): Boolean = runCatching {
        val names = mutableListOf<String>()
        cert.subjectAlternativeNames?.forEach { entry ->
            val type = entry.getOrNull(0) as? Int ?: return@forEach
            if (type == 2) (entry.getOrNull(1) as? String)?.let(names::add)
        }
        cert.subjectX500Principal.name
            .split(',')
            .map(String::trim)
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)
            ?.let(names::add)
        names.any { matches(it.lowercase().trimEnd('.'), host.lowercase().trimEnd('.')) }
    }.getOrDefault(false)

    private fun matches(pattern: String, host: String): Boolean {
        if (pattern == host) return true
        if (!pattern.startsWith("*.")) return false
        val suffix = pattern.substring(1) // ".example.com"
        if (!host.endsWith(suffix)) return false
        // 通配只覆盖一层标签
        return !host.dropLast(suffix.length).contains('.')
    }
}
