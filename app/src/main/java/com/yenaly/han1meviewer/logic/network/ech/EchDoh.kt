package com.yenaly.han1meviewer.logic.network.ech

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import com.yenaly.han1meviewer.HANIME_BACKUP_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.HANIME_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.DohResolverIdentity
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
 * 核心 Hanime host 使用 Cloudflare 官方共享 ECH 活源 `cloudflare-ech.com`：CNAME 别名里
 * 手动发布的 ECH 值可能比 Cloudflare 密钥轮换更旧，重新读取别名仍可能拿到同一把失效密钥。
 * 其他 hostname 仍只使用自身可达的 HTTPS(65) 记录。查询统一走用户已配置的 DoH Gateway
 * 与 bootstrap IP，不旁路解析器。核心 host 在配置不可用时 fail-closed；不切 HTTP 或关闭证书校验。
 */
object EchDoh {

    private const val TAG = "HY-ECH-DOH"

    private const val MAX_TTL_MS = 3_600_000L
    private const val MAX_CNAME_DEPTH = 16
    private const val LIVE_SOURCE_HOST = "cloudflare-ech.com"
    private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    @Volatile
    private var observedResolverIdentity: DohResolverIdentity? = null
    @Volatile
    private var cachedResolver: Dns? = null
    @Volatile
    private var cachedResolverIdentity: DohResolverIdentity? = null
    @Volatile
    private var cachedBootstrapClient: OkHttpClient? = null
    @Volatile
    private var cachedBootstrapTimeoutSeconds: Int? = null

    private fun bootstrapClient(timeoutSeconds: Int): OkHttpClient {
        cachedBootstrapClient?.let {
            if (cachedBootstrapTimeoutSeconds == timeoutSeconds) return it
        }
        return synchronized(this) {
            cachedBootstrapClient?.takeIf { cachedBootstrapTimeoutSeconds == timeoutSeconds }
                ?: OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .build()
                    .also {
                        cachedBootstrapTimeoutSeconds = timeoutSeconds
                        cachedBootstrapClient = it
                    }
        }
    }

    /** Clear resolver-bound caches whenever the effective endpoint, bootstrap pins, or timeout changes. */
    private fun observeResolverIdentity(identity: DohResolverIdentity) {
        if (observedResolverIdentity == identity) return
        synchronized(this) {
            if (observedResolverIdentity == identity) return
            observedResolverIdentity = identity
            cachedResolver = null
            cachedResolverIdentity = null
            cachedBootstrapClient = null
            cachedBootstrapTimeoutSeconds = null
            dnsCache.clear()
            echCache.clear()
            ConscryptEch.clearConfiguredEchHosts()
            ConscryptEch.clearEchUnavailable()
        }
    }

    /** Official DoH resolver for A/AAAA lookups; the same selected URL is used for HTTPS-record ECH lookups. */
    private fun resolver(): Dns? {
        val identity = DohConfig.resolverIdentity() ?: return null
        observeResolverIdentity(identity)
        cachedResolver?.let { if (cachedResolverIdentity == identity) return it }
        return synchronized(this) {
            if (observedResolverIdentity != identity) return@synchronized null
            cachedResolver?.let { if (cachedResolverIdentity == identity) return@synchronized it }
            val pins = identity.bootstrapIps
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val resolver = runCatching {
                DnsOverHttps.Builder()
                    .client(bootstrapClient(identity.timeoutSeconds))
                    .url(identity.url.toHttpUrl())
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
                return@synchronized null
            }
            cachedResolverIdentity = identity
            cachedResolver = resolver
            resolver
        }
    }

    /** 用户改了 DoH 设置后调用，让解析器与 ECH 缓存都跟着刷新 */
    fun invalidateAll() {
        synchronized(this) {
            cachedResolver = null
            cachedResolverIdentity = null
            observedResolverIdentity = null
            cachedBootstrapClient = null
            cachedBootstrapTimeoutSeconds = null
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
     * 核心 Hanime host 共用官方 ECH live source；其他 host 使用自身 HTTPS(65)/可达 CNAME 记录。
     * 缓存以 DNS Answer 的 TTL 为上限，wire 已含 RFC 9460 的 2 字节列表长度前缀，可直接交给 Conscrypt。
     */
    fun echConfigList(host: String): ByteArray? {
        val name = normalizeDnsName(host)
        if (!EchHosts.shouldTryEch(name)) return null
        val resolverIdentity = DohConfig.resolverIdentity() ?: return null
        observeResolverIdentity(resolverIdentity)
        val sourceName = if (EchHosts.isCoreDomain(name)) LIVE_SOURCE_HOST else name
        val now = System.currentTimeMillis()
        echCache[sourceName]?.let {
            if (it.expireAt > now) return it.wire
            echCache.remove(sourceName, it)
        }

        val hit = try {
            fetchConfig(resolverIdentity, sourceName)
        } catch (t: Throwable) {
            Log.w(TAG, "ECH HTTPS-record query failed for host=$name source=$sourceName: ${t.javaClass.simpleName}")
            null
        }
        if (hit == null) {
            EchTrace.event("no valid ECHConfig in HTTPS record for host=$name source=$sourceName")
            return null
        }
        val (wire, ttlMs) = hit
        if (ttlMs > 0L) echCache[sourceName] = EchEntry(wire, now + ttlMs)
        EchTrace.event("ECHConfig loaded for host=$name source=$sourceName (${wire.size}B, ttl=${ttlMs}ms)")
        return wire
    }

    /** Follow only CNAMEs reachable from the queried owner, then parse its HTTPS(65) record. */
    private fun fetchConfig(resolverIdentity: DohResolverIdentity, name: String): Pair<ByteArray, Long>? {
        val body = query(resolverIdentity, name, "HTTPS") ?: return null
        val answers = runCatching { JSONObject(body).optJSONArray("Answer") }.getOrNull()
            ?: return null
        var owner = name
        var cnameTtlSeconds = Long.MAX_VALUE
        val visited = HashSet<String>()
        repeat(MAX_CNAME_DEPTH + 1) {
            if (!visited.add(owner)) return null
            for (index in 0 until answers.length()) {
                val answer = answers.optJSONObject(index) ?: continue
                if (answer.optInt("type", -1) != 65) continue
                if (normalizeDnsName(answer.optString("name", "")) != owner) continue

                val b64 = extractEchParam(answer.optString("data", "")) ?: continue
                val flags = if ('-' in b64 || '_' in b64) Base64.URL_SAFE else Base64.DEFAULT
                val wire = runCatching { Base64.decode(b64, flags) }.getOrNull()
                if (wire == null || !isValidEchConfigList(wire)) {
                    Log.w(TAG, "invalid ECHConfigList in HTTPS record for host=$owner")
                    continue
                }

                val recordTtlSeconds = answer.optLong("TTL", 0L).coerceAtLeast(0L)
                val ttlSeconds = minOf(cnameTtlSeconds, recordTtlSeconds)
                val ttlMs = if (ttlSeconds > 0L) {
                    minOf(ttlSeconds, MAX_TTL_MS / 1000L) * 1000L
                } else {
                    0L
                }
                return wire to ttlMs
            }

            val cnameRecords = (0 until answers.length()).mapNotNull { index ->
                val answer = answers.optJSONObject(index) ?: return@mapNotNull null
                if (answer.optInt("type", -1) != 5 ||
                    normalizeDnsName(answer.optString("name", "")) != owner
                ) return@mapNotNull null
                val target = normalizeDnsName(answer.optString("data", ""))
                if (!isValidDnsName(target)) return@mapNotNull null
                target to answer.optLong("TTL", 0L).coerceAtLeast(0L)
            }
            val targets = cnameRecords.map { it.first }.distinct()
            if (targets.size != 1) return null
            owner = targets.single()
            cnameTtlSeconds = minOf(cnameTtlSeconds, cnameRecords.minOf { it.second })
        }
        return null
    }

    private fun isValidDnsName(name: String): Boolean =
        name.length in 1..253 && name.split('.').all { it.matches(DNS_LABEL) }

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

    /** ECH_REJECTED 后丢弃对应 source cache；核心 Hanime 会从官方 live source 重新取值。 */
    fun invalidateEch(host: String) {
        val name = normalizeDnsName(host)
        val sourceName = if (EchHosts.isCoreDomain(name)) LIVE_SOURCE_HOST else name
        echCache.remove(sourceName)
        ConscryptEch.clearConfiguredEchHost(name)
        EchTrace.event("invalidated ECH cache for host=$name source=$sourceName")
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

        val resolverIdentity = DohConfig.resolverIdentity() ?: return emptyList()
        observeResolverIdentity(resolverIdentity)
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
    private fun query(resolverIdentity: DohResolverIdentity, host: String, type: String): String? {
        // DoH 网关域名本身不能走系统 DNS（被污染），用 bootstrap IP 钉住
        val pins = resolverIdentity.bootstrapIps
            .mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
        val bootstrapClient = bootstrapClient(resolverIdentity.timeoutSeconds)
        val client = if (pins.isNotEmpty()) {
            val pinnedDns = object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = pins
            }
            bootstrapClient.newBuilder().dns(pinnedDns).build()
        } else {
            bootstrapClient
        }
        val requestUrl = resolverIdentity.url.toHttpUrl().newBuilder()
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
