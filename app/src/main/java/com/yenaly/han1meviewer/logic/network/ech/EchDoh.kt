package com.yenaly.han1meviewer.logic.network.ech

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import com.yenaly.han1meviewer.HANIME_BACKUP_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.HANIME_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DoH 层：取目标域名的真实 IP（绕大陆 DNS 污染）+ 取 ECHConfigList。
 *
 * 与 App 原有 DoH 设施**共用同一份配置**（[DohConfig]：预设/自定义 URL、bootstrap IP、超时），
 * 用户在设置里改 DoH 时这里自动跟着变，不再有第二份硬编码端点。
 *
 * 为什么还要自己发一次查询：`DnsOverHttps` 只做 A/AAAA，而 ECH 配置在 **HTTPS(65)** 记录里，
 * 所以这里用同一个 bootstrap 客户端发 JSON 查询（`application/dns-json`）并解析 `ech=`。
 *
 * 只使用所请求 hostname 自身的 HTTPS(65) 记录。核心 Hanime host 与被服务器 ECH_REJECTED
 * 证实需要 ECH 的 host 在配置不可用时 fail-closed；其他 host 无有效配置时使用普通、仍验证
 * 证书和 hostname 的 HTTPS TLS，不切到 HTTP，也不关闭证书校验。
 */
object EchDoh {

    private const val TAG = "HY-ECH-DOH"

    private const val MAX_TTL_MS = 3_600_000L

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DohConfig.timeoutSeconds().toLong(), TimeUnit.SECONDS)
            .readTimeout(DohConfig.timeoutSeconds().toLong(), TimeUnit.SECONDS)
            .build()
    }

    /** 官方 DoH 解析器：A/AAAA + TTL 缓存；DoH 网关自身用 bootstrap IP 钉住，绕开污染 */
    @Volatile
    private var cachedResolver: Dns? = null
    @Volatile
    private var cachedResolverUrl: String? = null

    /**
     * 取（可能随用户设置变化的）DoH 解析器。
     * 用户在设置页换预设/自定义 URL 后，这里按 URL 变化重建 —— 否则还打旧网关。
     */
    /**
     * Target-host HTTPS/SVCB lookup endpoint, kept independent from the ordinary DNS toggle.
     * Uses the app's existing DoH URL and bootstrap configuration without changing either.
     */
    private fun echDohUrl(): String {
        val custom = Preferences.dohCustomUrl.trim()
        return if (custom.isNotEmpty()) custom else DohConfig.selectedPreset().url
    }

    private fun resolver(): Dns? {
        val url = echDohUrl()
        cachedResolver?.let { if (cachedResolverUrl == url) return it }
        return synchronized(this) {
            cachedResolver?.let { if (cachedResolverUrl == url) return it }
            val pins = DohConfig.bootstrapIps()
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val r = runCatching {
                DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(url.toHttpUrl())
                    .apply { if (pins.isNotEmpty()) bootstrapDnsHosts(*pins.toTypedArray()) }
                    // ⚠️ 必须启用 IPv6 —— 这是「Chrome 打得开、App 打不开」的根因。
                    // 实测：同一张图走 IPv6 直连（2a02:6ea0:c77a::47）返回 200/47480B，
                    // 走 IPv4（37.19.194.81）也返回 200，但**移动对封锁域名的 SNI 阻断
                    // 只在 IPv4 侧生效**。Chrome 优先 IPv6 所以打得开；而这里曾经显式
                    // 关闭 IPv6，App 只能拿到 A 记录 → 必然走 IPv4 → 必然被 RST。
                    // 关闭的代价是 App 比浏览器"笨"，遇到按 IP 版本区别对待的封锁就必死。
                    .includeIPv6(true)
                    .build()
            }.getOrElse {
                Log.w(TAG, "DoH resolver build failed (${it.javaClass.simpleName})")
                return null
            }
            cachedResolverUrl = url
            cachedResolver = r
            r
        }
    }

    /** 用户改了 DoH 设置后调用，让解析器与 ECH 缓存都跟着刷新 */
    fun invalidateAll() {
        synchronized(this) {
            cachedResolver = null
            cachedResolverUrl = null
            dnsCache.clear()
        }
        echCache.clear()
        ConscryptEch.clearConfiguredEchHosts()
        ConscryptEch.clearEchUnavailable()
    }

    // ---------------- ECH 配置 ----------------

    private class EchEntry(val wire: ByteArray, val expireAt: Long)

    private val echCache = ConcurrentHashMap<String, EchEntry>()

    /**
     * 只取 [host] 自身 HTTPS(65) Answer 中的 ECHConfigList；不跨 hostname 借用配置。
     * 返回的 wire 已含 RFC 9460 的 2 字节列表长度前缀，可直接交给 Conscrypt。
     */
    fun echConfigList(host: String): ByteArray? {
        val name = normalizeDnsName(host)
        if (!EchHosts.shouldTryEch(name)) return null
        val now = System.currentTimeMillis()
        echCache[name]?.let {
            if (it.expireAt > now) return it.wire
            echCache.remove(name, it)
        }

        val hit = try {
            fetchConfig(echDohUrl(), name)
        } catch (t: Throwable) {
            Log.w(TAG, "ECH HTTPS-record query failed for host=$name: ${t.javaClass.simpleName}")
            null
        }
        if (hit == null) {
            EchTrace.event("no valid ECHConfig in HTTPS record for host=$name")
            return null
        }
        val (wire, ttlMs) = hit
        if (ttlMs > 0L) echCache[name] = EchEntry(wire, now + ttlMs)
        EchTrace.event("ECHConfig loaded for host=$name (${wire.size}B, ttl=${ttlMs}ms)")
        return wire
    }

    /** Parse only matching HTTPS(65) answer records; TTL is never extended beyond the RR TTL. */
    private fun fetchConfig(url: String, name: String): Pair<ByteArray, Long>? {
        val body = query(url, name, "HTTPS") ?: return null
        val answers = runCatching { JSONObject(body).optJSONArray("Answer") }.getOrNull()
            ?: return null
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            if (answer.optInt("type", -1) != 65) continue
            if (normalizeDnsName(answer.optString("name", "")) != name) continue

            val b64 = extractEchParam(answer.optString("data", "")) ?: continue
            val flags = if ('-' in b64 || '_' in b64) Base64.URL_SAFE else Base64.DEFAULT
            val wire = runCatching { Base64.decode(b64, flags) }.getOrNull()
            if (wire == null || !isValidEchConfigList(wire)) {
                Log.w(TAG, "invalid ECHConfigList in HTTPS record for host=$name")
                continue
            }

            val ttlSeconds = answer.optLong("TTL", 0L)
            val ttlMs = if (ttlSeconds > 0L) {
                minOf(ttlSeconds, MAX_TTL_MS / 1000L) * 1000L
            } else {
                0L
            }
            return wire to ttlMs
        }
        return null
    }

    private fun extractEchParam(data: String): String? {
        val match = Regex(
            """(?:^|\s)ech=(?:"([A-Za-z0-9+/_=-]+)"|([A-Za-z0-9+/_=-]+))(?=\s|$)""",
            RegexOption.IGNORE_CASE,
        ).find(data) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).takeIf { it.isNotEmpty() }
    }

    /** Check the outer ECHConfigList vector and every version/length-delimited ECHConfig. */
    private fun isValidEchConfigList(wire: ByteArray): Boolean {
        if (wire.size < 7) return false
        val listLength = ((wire[0].toInt() and 0xff) shl 8) or (wire[1].toInt() and 0xff)
        if (listLength != wire.size - 2) return false

        var offset = 2
        var configCount = 0
        while (offset < wire.size) {
            if (wire.size - offset < 4) return false
            val configLength = ((wire[offset + 2].toInt() and 0xff) shl 8) or
                (wire[offset + 3].toInt() and 0xff)
            if (configLength == 0 || configLength > wire.size - offset - 4) return false
            offset += 4 + configLength
            configCount++
        }
        return configCount > 0 && offset == wire.size
    }

    /**
     * ECH 被服务器拒绝（密钥轮换 / 配置失效）后清缓存，下次重新从 DoH 取。
     */
    fun invalidateEch(host: String) {
        val name = normalizeDnsName(host)
        echCache.remove(name)
        ConscryptEch.clearConfiguredEchHost(name)
    }

    private fun normalizeDnsName(host: String): String = host.trim().trimEnd('.').lowercase(java.util.Locale.ROOT)

    // ---------------- DNS ----------------

    /**
     * A/AAAA 记录缓存（5 分钟）。
     *
     * 加它**不是**因为怕打爆 DoH 端 —— Cloudflare 侧 24 小时 2000 万请求都不算事，
     * 容量完全不是约束。留它是为了省**本机**的一次 HTTPS 往返：DoH 每次 lookup
     * 都是一整个请求，而系统 DNS 有本地缓存做对照 —— 不缓存会让每个新连接
     * 多等一次往返。加上 OkHttp 自身的连接池，实际 lookup 频率本来就很低。
     *
     * 只在解析**成功**时缓存；失败不缓存，下次立刻重试。
     */
    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()

    private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L

    private data class DnsEntry(val addrs: List<InetAddress>, val expireAt: Long)

    fun resolve(host: String): List<InetAddress> {
        // 媒体 CDN 自定义节点 IP：用户在设置中选择区域或手动填写后，
        // 两个等价域名（vdownload.hembed.com / 1497203185.rsc.cdn77.org）直接解析到指定 IP。
        val lowerHost = host.lowercase()
        if (lowerHost == HANIME_MEDIA_CDN_HOST.lowercase() ||
            lowerHost == HANIME_BACKUP_MEDIA_CDN_HOST.lowercase()
        ) {
            val nodeIps = Preferences.mediaCdnNodeIps
            if (nodeIps.isNotEmpty()) {
                val addrs = nodeIps.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                if (addrs.isNotEmpty()) {
                    EchTrace.event("CDN 节点 IP 直连 $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
                    return addrs
                }
            }
        }

        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (it.expireAt > now) return it.addrs }

        val r = resolver() ?: return emptyList()
        return try {
            val addrs = r.lookup(host)
            if (addrs.isNotEmpty()) dnsCache[host] = DnsEntry(addrs, now + DNS_CACHE_TTL_MS)
            EchTrace.event("doh resolve $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
            addrs
        } catch (t: Throwable) {
            Log.w(TAG, "doh resolve failed for $host: ${t.message}")
            emptyList()
        }
    }

    /** 底层 JSON 查询（仅用于 HTTPS(65) 记录；A/AAAA 交给 DnsOverHttps） */
    private fun query(dohUrl: String, host: String, type: String): String? {
        // DoH 网关域名本身不能走系统 DNS（被污染），用 bootstrap IP 钉住
        val pins = DohConfig.bootstrapIps()
            .mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
        val client = if (pins.isNotEmpty()) {
            val pinnedDns = object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = pins
            }
            bootstrapClient.newBuilder().dns(pinnedDns).build()
        } else {
            bootstrapClient
        }
        val requestUrl = dohUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", host)
            .addQueryParameter("type", type)
            .build()
        val req = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "doh $type HTTP ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }
}

/**
 * **所有域名**都优先走自有 DoH 解析（用户定调）。
 *
 * 为什么不是只保护核心域名：「DNS 被污染」和「目标支不支持 ECH」是**两件事** ——
 * 域名不支持 ECH、只能走明文，一样会被污染到假 IP 而连不上。
 * 所以 DoH 是**解析层**的事，与 ECH 的覆盖面无关。
 *
 * 规格（用户明确）：所有 IP 只从 DoH 拿，不走系统 DNS。DoH 失败直接抛异常，
 * 不回落（系统 DNS 被污染，回落等于连假 IP）。
 *
 * 性能：`EchDoh.resolve` 内部带 5 分钟 A 记录缓存，且 OkHttp 有连接池、不会频繁 lookup，
 * 所以全量走 DoH 不会把解析变成瓶颈。
 */
class EchDns(private val fallback: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addrs = runCatching { EchDoh.resolve(hostname) }.getOrNull()
        if (!addrs.isNullOrEmpty()) return addrs

        // 只从 DoH 拿 IP，不回落系统 DNS（用户规格）
        throw UnknownHostException("DoH 解析失败（只走 DoH）：$hostname")
    }

    private companion object {
        const val TAG = "HY-ECH-DOH"
    }
}
