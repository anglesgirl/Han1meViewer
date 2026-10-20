package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
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
 * ECH 活值通过独立的公共纯 IP DoH 查询 HTTPS(65) 记录，
 * 不使用用户网关、引导 IP 或普通 DNS 开关。
 * 主域名 A/AAAA 保留用户可信 DoH；其他域名只走公共纯 IP DoH。
 * 公共端点全部失败时不回退自有网关，核心域名继续 fail-closed。
 */
object EchDoh {

    private const val TAG = "HY-ECH-DOH"

    /** 失败冷却：避免每个请求都去打一次注定失败的 DoH */
    private const val FAIL_COOLDOWN_MS = 5_000L

    /** 两个 ECH 活值基准；查询的是它们的 HTTPS(65)，不是目标主域名的公共 DNS。 */
    private const val LIVE_SOURCE_HOST = "cloudflare-ech.com"
    private val LIVE_SOURCE_HOSTS = listOf(LIVE_SOURCE_HOST, "crypto.cloudflare.com")
    private const val ECH_CACHE_TTL_MS = 60 * 60 * 1000L

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
     * 此地址仅供主域名 A/AAAA 和辅助 TXT 查询使用；ECH 活值不走这里。
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
                Log.w(TAG, "DoH 解析器构建失败（url=$url）：${it.message}")
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
        synchronized(echFetchLock) {
            echCache.clear()
            echRetryAt = 0L
            preferredEndpoint = null
            endpointRetryAt.clear()
        }
        ConscryptEch.clearEchUnavailable()
    }

    // ---------------- ECH 配置 ----------------

    private class EchEntry(val wire: ByteArray, val expireAt: Long)

    private val echCache = ConcurrentHashMap<String, EchEntry>()
    private val echFetchLock = Any()
    private var echRetryAt = 0L

    // ECH 与普通域名共享健康端点及连接池；不再每次重新抽到慢/不可达端点。
    @Volatile private var preferredEndpoint: String? = null
    private val endpointRetryAt = ConcurrentHashMap<String, Long>()
    private fun elapsedMs() = System.nanoTime() / 1_000_000L

    private fun endpoints(): List<String> {
        val now = elapsedMs()
        val preferred = preferredEndpoint
        return (listOfNotNull(preferred) + ECH_DOH_IPS).distinct()
            .filter { (endpointRetryAt[it] ?: 0L) <= now }
    }

    private fun endpointSucceeded(ip: String) {
        endpointRetryAt.remove(ip)
        preferredEndpoint = ip
    }

    private fun endpointFailed(ip: String) {
        endpointRetryAt[ip] = elapsedMs() + FAIL_COOLDOWN_MS
    }

    /** 被服务器拒过的域名：改用「它自己的记录」优先，别一直拿同一份撞。 */
    private val ownFirst = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 取 ECH 活值的候选：国内三家的纯 IP 端点。
     * IP URL 不需要解析网关域名；TLS 证书仍必须对该 IP 有效。
     * 不手工覆写 Host/SNI：由 OkHttp 按 IP URL 发送标准 HTTP 请求。
     * 统一使用标准 DNS wire 格式，不依赖各家的 JSON 扩展。
     * 策略：复用最近成功端点，冷启动按候选顺序尝试；串行切换、失败短暂冷却。
     */
    private val ECH_DOH_IPS = listOf(
        "223.5.5.5", "223.6.6.6",           // 阿里
        "1.12.12.12", "120.53.53.53",       // 腾讯
        "101.198.193.29", "101.198.192.33", // 360
    )

    private const val ECH_ONE_TIMEOUT_MS = 2500L
    /**
     * ECH 专用客户端：IP URL 不需要 DNS；禁止跳转，避免被引到域名网关。
     * 使用默认 TLS 证书链/IP 校验，不关闭证书校验，也不接入 ECH 传输层。
     */
    private val echIpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("ECH DoH must use an IP literal: $hostname")
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(ECH_ONE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(ECH_ONE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(ECH_ONE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
    /** 同一 IP 传输失败时直接换端点，不为第二个源再等一次相同的超时。 */
    private fun fetchLiveEch(): Pair<ByteArray, Long>? {
        for (ip in endpoints()) {
            for (source in LIVE_SOURCE_HOSTS) {
                val result = try {
                    queryEchWire(ip, source)
                } catch (e: Exception) {
                    endpointFailed(ip)
                    Log.i(TAG, "live ech via $ip source=$source failed: ${e.message}")
                    break
                }
                // 传输成功但该基准没有 ECH 时，仍尝试同一端点的另一个基准。
                endpointSucceeded(ip)
                if (result != null) {
                    EchTrace.event("live ech via IP $ip source=$source: ${result.first.size} bytes")
                    return result
                }
            }
        }
        EchTrace.event("live ech: 公共 IP DoH 的两个基准源均失败，不回退用户网关")
        return null
    }

    /** 非主域名 A/AAAA 的解析器，与 ECH 共用无域名引导的纯 IP 传输。 */
    private val publicResolvers: Map<String, Dns> by lazy {
        ECH_DOH_IPS.associateWith { ip ->
            DnsOverHttps.Builder()
                .client(echIpClient)
                .url("https://$ip/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName(ip))
                .includeIPv6(true)
                .build()
        }
    }

    private fun resolvePublic(host: String): List<InetAddress> {
        for (ip in endpoints()) {
            val dns = publicResolvers.getValue(ip)
            val addrs = try {
                dns.lookup(host)
            } catch (e: Exception) {
                endpointFailed(ip)
                Log.i(TAG, "public DoH $ip failed for $host: ${e.message}")
                continue
            }
            if (addrs.isNotEmpty()) {
                endpointSucceeded(ip)
                EchTrace.event("public doh $ip resolve $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
                return addrs
            }
        }
        return emptyList()
    }

    /** 建 DNS 查询（type 65 = HTTPS）。 */
    private fun buildQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { lb -> out.write(lb.length); out.write(lb.toByteArray()) }
        out.write(0)
        out.write(byteArrayOf(0x00, 65, 0x00, 0x01))
        return out.toByteArray()
    }

    /** 纯 IP + wire 的 DoH 查询（不手工覆写 Host 头）。 */
    private fun queryEchWire(ip: String, name: String): Pair<ByteArray, Long>? {
        val b64 = android.util.Base64.encodeToString(
            buildQuery(name), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        ).trimEnd('=')
        val req = Request.Builder()
            .url("https://$ip/dns-query?dns=$b64")
            .header("accept", "application/dns-message")
            .build()
        val wire = echIpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("DoH HTTP ${resp.code}")
            resp.body?.bytes() ?: throw java.io.IOException("Empty DoH response")
        }
        return parseSvcbEch(wire)
    }

    /** 解析应答里的 type=65 记录，走 SvcParams 取 key=5（ech）；返回值**含 2 字节长度前缀**。 */
    private fun parseSvcbEch(msg: ByteArray): Pair<ByteArray, Long>? {
        if (msg.size < 12) return null
        var i = 12
        while (i < msg.size && msg[i].toInt() != 0) i += (msg[i].toInt() and 0xFF) + 1
        i += 5
        val ancount = ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)
        for (n in 0 until ancount) {
            if (i + 12 > msg.size) return null
            if ((msg[i].toInt() and 0xC0) == 0xC0) i += 2
            else { while (i < msg.size && msg[i].toInt() != 0) i += (msg[i].toInt() and 0xFF) + 1; i += 1 }
            val type = ((msg[i].toInt() and 0xFF) shl 8) or (msg[i + 1].toInt() and 0xFF)
            val ttl = (((msg[i + 4].toInt() and 0xFF).toLong() shl 24) or
                ((msg[i + 5].toInt() and 0xFF).toLong() shl 16) or
                ((msg[i + 6].toInt() and 0xFF).toLong() shl 8) or
                (msg[i + 7].toInt() and 0xFF).toLong())
            val rdlen = ((msg[i + 8].toInt() and 0xFF) shl 8) or (msg[i + 9].toInt() and 0xFF)
            val rdata = i + 10
            if (type == 65 && rdlen > 4 && rdata + rdlen <= msg.size) {
                var j = rdata + 2
                while (j < rdata + rdlen && msg[j].toInt() != 0) j += (msg[j].toInt() and 0xFF) + 1
                j += 1
                while (j + 4 <= rdata + rdlen) {
                    val key = ((msg[j].toInt() and 0xFF) shl 8) or (msg[j + 1].toInt() and 0xFF)
                    val len = ((msg[j + 2].toInt() and 0xFF) shl 8) or (msg[j + 3].toInt() and 0xFF)
                    if (key == 5 && len > 0) {
                        val ech = msg.copyOfRange(j + 4, j + 4 + len)
                        val ttlMs = ttl * 1000
                        return ech to ttlMs
                    }
                    j += 4 + len
                }
            }
            i = rdata + rdlen
        }
        return null
    }

    /** 域名 → 是否解析到 CF 边缘段。缓存判定结果，避免每次建连都重复解析。 */
    private val cfHostCache = ConcurrentHashMap<String, Boolean>()

    /** IP → 是否属于 Cloudflare。按地址缓存，避免每个新连接都查一次 ASN。 */
    private val asnCache = ConcurrentHashMap<String, Boolean>()

    private const val CLOUDFLARE_ASN = 13335

    /**
     * 该 IP 是否属于 Cloudflare（**AS13335**）—— 决定要不要给它注入 ECH。
     *
     * 为什么用 ASN 而不是官方 IP 段表：段表要手工维护、而且**会漏** ——
     * 新增段、以及 CF「中国网络」的国内段都不在 `ips-v4` 里，漏判会让本该受保护的
     * 域名退回明文。ASN 归属由 IRRd 权威数据决定，一次查询覆盖它的全部段。
     * 实测：CF 站点（含 cloudflare-ech.com、bgm.tv、hanime1.me、javchu.com）全是 13335，
     * 而 CDN77 是 60068、站方自建 VPS 是 30058、Google 是 15169。
     *
     * 查询走 Team Cymru 的 DNS 反查（纯 DNS，与我们自己的 DoH 同一条路，
     * 手机上不需要任何额外依赖）：
     *
     *     <反转 IP>.origin.asn.cymru.com   TXT
     *     → "13335 | 104.26.0.0/20 | US | arin | 2014-03-28"
     *
     * IPv6 不参与判定（Cymru 的 v6 反查是另一套 nibble 形式），由调用方只看 IPv4。
     */
    private fun isCloudflareIp(ip: InetAddress): Boolean {
        if (ip.address.size != 4) return false
        val addr = ip.hostAddress ?: return false
        asnCache[addr]?.let { return it }
        val rev = addr.split(".").reversed().joinToString(".") + ".origin.asn.cymru.com"
        val body = try {
            query(echDohUrl(), rev, "TXT")
        } catch (t: Throwable) {
            null
        }
        val asn = body?.let { Regex(""""(\d{2,6})\s*\|""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        // 查不到（网络或服务异常）时按「是 CF」处理：宁可白试一次，也不能误伤受保护域名
        if (asn == null) return true
        val cf = asn == CLOUDFLARE_ASN
        asnCache[addr] = cf
        return cf
    }

    /**
     * 该域名是否解析到 Cloudflare 边缘段 —— **决定要不要注入 ECH**。
     *
     * ⚠️ 解析失败时**返回 true（不拦）**：宁可白注入一次，也不能因为一次解析失败
     * 就让本该受保护的域名退回明文 —— 核心域名是 fail-closed 的，误判等于断网。
     */
    private fun isCloudflareHost(host: String): Boolean {
        cfHostCache[host]?.let { return it }
        val addrs = try {
            resolve(host)
        } catch (t: Throwable) {
            emptyList()
        }
        if (addrs.isEmpty()) return true
        // 只看 IPv4 的归属：Cymru 反查是 IPv4 形式，域名只要有一个 IPv4 判为 CF 就算 CF。
        // 只有 IPv6 的域名（罕见）不拦 —— 宁可白试一次，不能误伤。
        val v4 = addrs.filter { it.address.size == 4 }
        val cf = if (v4.isEmpty()) true else v4.any { isCloudflareIp(it) }
        cfHostCache[host] = cf
        if (cf) {
            EchTrace.event("CF 域名（AS13335），注入 ECH: $host")
        } else {
            EchTrace.event(
                "非 CF 域名，跳过 ECH（提前判定）: $host -> " +
                    addrs.joinToString(", ") { it.hostAddress ?: "?" }
            )
        }
        return cf
    }

    /**
     * 取 ECHConfigList（RFC 9460 wire 格式，**已含 2 字节长度前缀**，可直接喂 Conscrypt）。
     *
     * 规格（用户定义）：
     * - 从 cloudflare-ech.com / crypto.cloudflare.com 经公共纯 IP DoH 取，缓存 1 小时
     * - 所有域名都用这一份配置尝试 ECH
     * - 失败由调用方标记不支持（内存，app 重启清空）
     *
     * @return null 表示 DoH 拿不到 —— 调用方据此处理
     */
    fun echConfigList(host: String): ByteArray? = synchronized(echFetchLock) {
        // 全部调用方共用一份抓取；冷启动图片/页面并发建连也不会重复请求。
        echCache[LIVE_SOURCE_HOST]?.let {
            if (it.expireAt > System.currentTimeMillis()) return@synchronized it.wire
        }
        if (elapsedMs() < echRetryAt) return@synchronized null
        val hit = try {
            fetchLiveEch()
        } catch (e: Exception) {
            Log.w(TAG, "ech query failed: ${e.message}")
            null
        }
        if (hit == null) {
            echRetryAt = elapsedMs() + FAIL_COOLDOWN_MS
            EchTrace.event("no ech config from $LIVE_SOURCE_HOST")
            return@synchronized null
        }
        val (wire, _) = hit
        // 从抓取完成开始缓存 1 小时，不持久化旧密钥，不允许明文回退。
        echCache[LIVE_SOURCE_HOST] = EchEntry(wire, System.currentTimeMillis() + ECH_CACHE_TTL_MS)
        echRetryAt = 0L
        EchTrace.event("ech config: ${wire.size} bytes（双基准源，缓存1h）")
        wire
    }

    /** 密钥被拒后立即重新获取，不受前次失败冷却影响。 */
    fun invalidateEch(host: String) {
        synchronized(echFetchLock) {
            echCache.remove(LIVE_SOURCE_HOST)
            echRetryAt = 0L
        }
    }

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

    private val dnsLocks = Array(32) { Any() }

    fun resolve(host: String): List<InetAddress> {
        val key = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
        return synchronized(dnsLocks[(key.hashCode() and Int.MAX_VALUE) % dnsLocks.size]) {
            resolveLocked(key)
        }
    }

    private fun resolveLocked(host: String): List<InetAddress> {
        // 媒体 CDN 自定义节点 IP：用户在设置中选择区域或手动填写后，
        // 两个等价域名（vdownload.hembed.com / 1497203185.rsc.cdn77.org）直接解析到指定 IP。
        val lowerHost = host.lowercase(java.util.Locale.ROOT).trimEnd('.')
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
        dnsCache[lowerHost]?.let { if (it.expireAt > now) return it.addrs }

        // 主域名及其子域可能被公共 DNS 污染，绝不向公共解析器查询或回退。
        val core = EchHosts.isCoreDomain(lowerHost)
        return try {
            val addrs = if (core) {
                val r = resolver() ?: return emptyList()
                r.lookup(lowerHost)
            } else {
                resolvePublic(lowerHost)
            }
            if (addrs.isNotEmpty()) dnsCache[lowerHost] = DnsEntry(addrs, now + DNS_CACHE_TTL_MS)
            EchTrace.event("${if (core) "trusted" else "public"} doh resolve $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
            addrs
        } catch (t: Throwable) {
            Log.w(TAG, "doh resolve failed for $host: ${t.message}")
            emptyList()
        }
    }

    /** 辅助 TXT/ASN JSON 查询；不用于 ECH 活值，A/AAAA 交给 DnsOverHttps。 */
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
        val req = Request.Builder()
            .url("$dohUrl?name=$host&type=$type")
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
 * DNS 分流：主域名及其子域只走用户可信 DoH，非主域名只走公共纯 IP DoH。
 * 公共查询仅用于 ECH 双基准及非主域名，绝不用于主域名 A/AAAA。
 * 两条路径失败均不回落系统 DNS；核心域名仍维持 ECH fail-closed。
 */
class EchDns(private val fallback: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addrs = runCatching { EchDoh.resolve(hostname) }.getOrNull()
        if (!addrs.isNullOrEmpty()) return addrs

        // 不回落系统 DNS；主域名也绝不回落公共 DoH。
        throw UnknownHostException("DoH 解析失败（只走 DoH）：$hostname")
    }

    private companion object {
        const val TAG = "HY-ECH-DOH"
    }
}
