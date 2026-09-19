package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.han1meviewer.Preferences
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
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

        private val getchuIps = listOf("210.155.150.166", "210.155.150.145")

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

        if (Preferences.useBuiltInHosts && HANIME_HOSTNAME.contains(hostname)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.map {
                    InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
                }
            }
            return NodePool.builtInHosts().map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        val dohUrl = DohConfig.resolveUrl()
        if (!dohUrl.isNullOrBlank()) {
            return runCatching { lookupByDoH(dohUrl, hostname) }
                .getOrElse {
                    Log.w("DOH", "lookup failed for $hostname: ${it.message}")
                    if (HANIME_HOSTNAME.contains(hostname)) emptyList() else Dns.SYSTEM.lookup(hostname)
                }
        }

        // 受保护域在「内置 Hosts 关 + DoH 关」时**不许回落系统 DNS**：
        // 那样会拿到污染地址并把 SNI 暴露出去（fail-closed：宁可连不上，也不明文暴露）。
        if (HANIME_HOSTNAME.contains(hostname)) {
            Log.w("DOH", "受保护域 $hostname 无可用解析途径（Hosts/DoH 均未启用），已按 fail-closed 拒绝")
            return emptyList()
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
            return NodePool.builtInHosts().distinct()
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
