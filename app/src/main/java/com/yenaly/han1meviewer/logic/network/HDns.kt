package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/10 010 17:01
 */
class HDns : Dns {

    private data class DohRuntimeConfig(
        val url: String,
        val bootstrapIps: List<String>,
        val timeoutSeconds: Int,
    )

    @Volatile
    private var cachedDohConfig: DohRuntimeConfig? = null

    @Volatile
    private var cachedDohDns: Dns? = null

    companion object {

        private val cloudFlareIps = listOf(
            "172.64.229.154", "162.159.0.1", "108.162.192.1", "172.64.33.1", "104.19.0.1",
            "2606:4700:3035::ac43:bb8d", "2606:4700:3030::6815:746", "2606:4700:3030::6815:714"
        )

        private val getchuIps = listOf("210.155.150.166", "210.155.150.145")

        /**
         * 视频 CDN 强制 IP 映射（绕过 NXDOMAIN 的权威 DNS）。
         * 2026-08-30 实测：t33.cdn2020.com 在 CF Gateway/Google 上 NXDOMAIN，
         * 1.1.1.1 / 腾讯 DoH 返回可用边缘 IP（TLS 证书 CN=t33.cdn2020.com 有效）。
         * 4 个边缘 IP 全部列出做冗余：103.143.178.6 / 103.143.179.2 / 103.143.178.7 / 103.143.179.1
         * 注意：CDN 边缘 IP 可能轮换，失效时需更新此表。
         */
        private val videoCdnIps = mapOf(
            "t33.cdn2020.com" to listOf("103.143.178.6", "103.143.179.2", "103.143.178.7", "103.143.179.1"),
        )

        private const val GETCHU_HOSTNAME = "www.getchu.com"

        /**
         * 添加DNS
         */
        private operator fun MutableMap<String, List<InetAddress>>.set(
            host: String, ips: List<String>,
        ) {
            this[host] = ips.map {
                InetAddress.getByAddress(host, InetAddress.getByName(it).address)
            }
        }

        /**
         * 解析自定义 IP 列表，逗号分隔
         */
        fun parseCustomIps(raw: String): List<String> {
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /**
         * 验证自定义 IP 列表格式是否有效
         * @return 无效 IP 的错误信息列表，为空表示全部有效
         */
        fun validateCustomHosts(raw: String): List<String> {
            val errors = mutableListOf<String>()
            if (raw.isBlank()) return errors
            val ips = parseCustomIps(raw)
            if (ips.isEmpty()) {
                errors.add("No IP addresses entered")
                return errors
            }
            ips.forEach { ip ->
                if (!isValidIpAddress(ip)) {
                    errors.add("Invalid IP address: \"$ip\"")
                }
            }
            return errors
        }

        private fun isValidIpAddress(ip: String): Boolean {
            return runCatching {
                val addr = InetAddress.getByName(ip)
                addr.hostAddress == ip || addr.hostAddress == ip.removePrefix("[")
                    .removeSuffix("]")
            }.getOrDefault(false)
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == GETCHU_HOSTNAME) {
            return getchuIps.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        // 视频 CDN 强制 IP：t33.cdn2020.com 的权威 DNS(quantum-dns)对部分 resolver 返回 NXDOMAIN
        // （实测 2026-08-30：CF Gateway NXDOMAIN、Google NXDOMAIN、阿里 SERVFAIL；
        //   1.1.1.1 / 腾讯 DoH 解析到 103.143.178.7，TLS 证书 CN=t33.cdn2020.com 有效）
        // 若不强制指定，播放器拿不到 IP 报 2001 NETWORK_CONNECTION_FAILED。
        videoCdnIps[hostname]?.let { ips ->
            Diagnostics.event("dns_pinned", mapOf("host" to hostname, "ips" to ips.joinToString(",")))
            return ips.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        if (Preferences.useBuiltInHosts && HANIME_HOSTNAME.contains(hostname)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.map {
                    InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
                }
            }
            return cloudFlareIps.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        val dohUrl = DohConfig.resolveUrl()
        if (!dohUrl.isNullOrBlank()) {
            return runCatching { lookupByDoH(dohUrl, hostname) }
                .getOrElse {
                    Log.w("DOH", "lookup failed for $hostname: ${it.message}")
                    // 关键区分：只有 ECH 目标域名才 fail-closed（防 DNS 污染 + 明文 SNI 暴露）
                    // 视频 CDN（如 t33.cdn2020.com，量子 CDN 非 CF）在用户 DoH 上可能 NXDOMAIN，
                    // 但系统 DNS 可解析且浏览器直连可播——这类域名必须回落系统 DNS，否则播放器永远 2001。
                    val echTarget = HANIME_HOSTNAME.contains(hostname) ||
                        hostname == GETCHU_HOSTNAME || hostname.endsWith(".getchu.com") ||
                        hostname.endsWith(".ubisoft.com")
                    Diagnostics.event(
                        "doh_failure", mapOf(
                            "host" to hostname,
                            "error_type" to it.javaClass.simpleName,
                            "error" to (it.message ?: "unknown"),
                            "fail_closed" to echTarget,
                            "fallback" to (!echTarget).toString(),
                        )
                    )
                    if (echTarget) {
                        // fail-closed：ECH 目标 DoH 不可达时绝不回落系统 DNS。
                        // 系统 DNS 在受污染网络下会返回被投毒结果，且会以明文 SNI 直连暴露访问目标。
                        // 这同时是维护者的总闸：停用下发的 DoH 端点即可让全部网络失效。
                        throw UnknownHostException("DoH unavailable for $hostname (fail-closed)")
                    }
                    // 非 ECH 目标（视频 CDN/封面图等）：DoH 解析失败时回落系统 DNS，保证播放器可用
                    Log.w("DOH", "fallback to system DNS for $hostname")
                    Diagnostics.event("dns_fallback", mapOf("host" to hostname))
                    return Dns.SYSTEM.lookup(hostname)
                }
        }

        return Dns.SYSTEM.lookup(hostname)
    }

    private fun lookupByDoH(dohUrl: String, hostname: String): List<InetAddress> {
        val config = DohRuntimeConfig(
            url = dohUrl,
            bootstrapIps = DohConfig.bootstrapIps(),
            timeoutSeconds = DohConfig.timeoutSeconds(),
        )
        val dns = getOrCreateDohDns(config)
        return dns.lookup(hostname).also {
            Log.i("DOH", it.toString())
            Diagnostics.event("doh_result", mapOf(
                "host" to hostname,
                "address_count" to it.size,
            ))
        }
    }

    fun lookupByDoHOnly(hostname: String): List<InetAddress> {
        val dohUrl = DohConfig.resolveUrl() ?: error("DoH is disabled")
        return lookupByDoH(dohUrl, hostname)
    }

    private fun getOrCreateDohDns(config: DohRuntimeConfig): Dns {
        val currentDns = cachedDohDns
        if (currentDns != null && cachedDohConfig == config) return currentDns

        synchronized(this) {
            val dnsAgain = cachedDohDns
            if (dnsAgain != null && cachedDohConfig == config) return dnsAgain

            val client = OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            val bootstrapHosts = config.bootstrapIps.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            val dnsBuilder = DnsOverHttps.Builder()
                .client(client)
                .url(config.url.toHttpUrl())
                .includeIPv6(true)
                .post(false)
                .resolvePrivateAddresses(true)
                .resolvePublicAddresses(true)
            if (bootstrapHosts.isNotEmpty()) {
                dnsBuilder.bootstrapDnsHosts(bootstrapHosts)
            }
            val dns = dnsBuilder.build()

            cachedDohConfig = config
            cachedDohDns = dns
            return dns
        }
    }

    fun getCDNList(host: String): List<String> {
        if (host == GETCHU_HOSTNAME) {
            return getchuIps.distinct()
        }

        if (Preferences.useBuiltInHosts && HANIME_HOSTNAME.contains(host)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.distinct()
            }
            return cloudFlareIps.distinct()
        }

        return runCatching {
            Dns.SYSTEM.lookup(host).map { it.hostAddress }.distinct()
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
    }

    @Volatile
    private var cachedCustomIps: List<String>? = null

    @Volatile
    private var cachedCustomIpsRaw: String? = null

    private fun resolveCustomIps(): List<String>? {
        val raw = Preferences.customHostsData
        if (raw.isBlank()) return null
        if (raw == cachedCustomIpsRaw && cachedCustomIps != null) {
            return cachedCustomIps
        }
        val result = parseCustomIps(raw)
        cachedCustomIpsRaw = raw
        cachedCustomIps = result
        return result
    }

}
