package com.yenaly.han1meviewer.logic.network.ech

import android.util.Base64
import android.util.Log
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
 * 失败一律 **fail-closed**：拿不到 ECH 配置时上层宁可不连，绝不回落明文 SNI。
 */
object EchDoh {

    private const val TAG = "HY-ECH-DOH"

    /** 失败冷却：避免每个请求都去打一次注定失败的 DoH */
    private const val FAIL_COOLDOWN_MS = 30_000L
    private const val MIN_TTL_MS = 60_000L
    private const val MAX_TTL_MS = 3_600_000L

    /**
     * 配置的唯一「活源」：CF 官方的 ECH 域名。
     *
     * 手写/注入到别处的 `ech=` 记录一旦过期，**再拉还是那份旧的**（记录没变、里面的密钥轮换掉了），
     * 拿它去握手只会被服务器拒绝（Conscrypt 抛 EchRejected）。所以受保护域名一律先取这份实时配置：
     * 跨 zone 注入实测可行，内层 SNI 仍是目标域名，SNI 不外泄。
     */
    private const val LIVE_SOURCE_HOST = "cloudflare-ech.com"

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
     * ECH 活值的取数地址：**与「普通 DNS 解析开关」解耦**。
     *
     * 关掉 DoH 只应停掉普通域名解析（那种场景下地址由内置 Hosts / 系统 DNS 提供），
     * 但 ECH 配置仍必须能取到 —— 否则受保护域 fail-closed，整个 App 直接没网
     * （用户实测：启用 Host + 关 DoH → 全 App 无网络）。
     * 这里始终可用：用户自定义 DoH（若有）→ 内置网关（其引导 IP 写死，不需要 DNS）。
     */
    private fun echDohUrl(): String {
        val custom = Preferences.dohCustomUrl.trim()
        return if (custom.isNotEmpty()) custom else DohConfig.presets.first().url
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
                    .includeIPv6(false)
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
        }
        echCache.clear()
        echFailed.clear()
    }

    // ---------------- ECH 配置 ----------------

    private class EchEntry(val wire: ByteArray, val expireAt: Long)

    private val echCache = ConcurrentHashMap<String, EchEntry>()
    private val echFailed = ConcurrentHashMap<String, Long>()

    /** 被服务器拒过的域名：改用「它自己的记录」优先，别一直拿同一份撞。 */
    private val ownFirst = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 取 ECH 活值的候选：**国内三家的纯 IP 端点**（实测三家均返回与 CF 官方逐字节相同的活值）。
     * 纯 IP = 不查 DNS、不被污染；证书直接对 IP 生效。
     * **不发 Host 头**：阿里带 Host 会直接失败（实测 http=000）。
     * **只发 wire**：三家都不支持 JSON（阿里/360 回 400、腾讯 UrlParameterError）。
     * 策略：随机挑一家试，失败换下一家（不同时打、不重复打同一家）。
     */
    private val ECH_DOH_IPS = listOf(
        "223.5.5.5", "223.6.6.6",           // 阿里
        "1.12.12.12", "120.53.53.53",       // 腾讯
        "101.198.193.29", "101.198.192.33", // 360
    )

    private const val ECH_ONE_TIMEOUT_MS = 2500L
    /** ECH 缓存下限：记录 TTL 只有 ~198s，但公钥实测稳定数天；被轮换时握手被拒会走 invalidateEch 自愈。 */
    private const val ECH_CACHE_MIN_MS = 60 * 60 * 1000L
    private const val ECH_CACHE_MAX_MS = 5 * 60 * 60 * 1000L

    /** 从随机一家纯 IP DoH 取官方活值（wire 格式，解析 SVCB 的 key=5）。 */
    private fun fetchLiveEch(): Pair<ByteArray, Long>? {
        for (ip in ECH_DOH_IPS.shuffled()) {
            val hit = runCatching { queryEchWire(ip, LIVE_SOURCE_HOST) }.getOrNull()
            if (hit != null) {
                Log.i(TAG, "live ech via $ip: ${hit.first.size} bytes, ttl=${hit.second}ms")
                return hit
            }
            Log.i(TAG, "live ech via $ip failed, next")
        }
        return null
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

    /** 纯 IP + wire 的 DoH 查询（绝不加 Host 头）。 */
    private fun queryEchWire(ip: String, name: String): Pair<ByteArray, Long>? {
        val b64 = android.util.Base64.encodeToString(
            buildQuery(name), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        ).trimEnd('=')
        val req = Request.Builder()
            .url("https://$ip/dns-query?dns=$b64")
            .header("accept", "application/dns-message")
            .build()
        val client = bootstrapClient.newBuilder()
            .connectTimeout(ECH_ONE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(ECH_ONE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(ECH_ONE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val wire = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes() ?: return null
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
                        val ttlMs = (ttl * 1000).coerceIn(ECH_CACHE_MIN_MS, ECH_CACHE_MAX_MS - 1) + 1
                        return ech to ttlMs
                    }
                    j += 4 + len
                }
            }
            i = rdata + rdlen
        }
        return null
    }

    /**
     * 取 ECHConfigList（RFC 9460 wire 格式，**已含 2 字节长度前缀**，可直接喂 Conscrypt）。
     * @return null 表示该域名没有 ECH 配置或 DoH 拿不到 —— 调用方据此 fail-closed
     */
    fun echConfigList(host: String): ByteArray? {
        val now = System.currentTimeMillis()
        echCache[host]?.let { if (it.expireAt > now) return it.wire }
        val failedAt = echFailed[host]
        if (failedAt != null && now - failedAt < FAIL_COOLDOWN_MS) return null

        // 注意：这里**不看** useDoH 开关。关 DoH 只是停普通解析，ECH 取数必须照旧，
        // 否则受保护域全部 fail-closed = 整个 App 没网络（用户实测过）。
        val url = echDohUrl()

        // 先走哪条路：默认官方活源；被翻过标志位的域名先用它自己的记录。
        val first = if (host != LIVE_SOURCE_HOST && !ownFirst.contains(host)) LIVE_SOURCE_HOST else host
        val second = if (first == host) LIVE_SOURCE_HOST else host
        val hit = try {
            // 活值优先：国内三家纯 IP（随机一家、失败换下一家）；全失败才回退自有网关的 JSON 链路
            if (first == LIVE_SOURCE_HOST) fetchLiveEch() ?: fetchConfig(url, first, now)
            else fetchConfig(url, first, now)
                ?: (if (second == LIVE_SOURCE_HOST) fetchLiveEch() else fetchConfig(url, second, now))
        } catch (t: Throwable) {
            Log.w(TAG, "ech query failed for $host: ${t.message}")
            null
        }
        if (hit == null) {
            Log.i(TAG, "no ech config for $host（已试：$first / $second）")
            echFailed[host] = now
            return null
        }
        val (wire, ttlMs) = hit
        echCache[host] = EchEntry(wire, now + ttlMs)
        echFailed.remove(host)
        Log.i(TAG, "ech config for $host: ${wire.size} bytes（源=$first）")
        return wire
    }

    /** 查某个域名的 HTTPS(65) 记录并解出配置：wire（含 2 字节长度前缀）+ 缓存时长。 */
    private fun fetchConfig(url: String, name: String, now: Long): Pair<ByteArray, Long>? {
        val body = query(url, name, "HTTPS") ?: return null
        val b64 = Regex("ech=([A-Za-z0-9+/=]+)").find(body)?.groupValues?.get(1)
        if (b64 == null) {
            Log.i(TAG, "no ech config in record of $name")
            return null
        }
        val ttl = Regex("\"TTL\"\\s*:\\s*(\\d+)").findAll(body)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .minOrNull() ?: 300L
        // ⚠️ 解码结果就是完整 wire（含长度前缀），不要再自己加一层
        val wire = Base64.decode(b64, Base64.DEFAULT)
        return wire to (ttl * 1000).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
    }

    /**
     * ECH 被服务器拒绝（密钥轮换 / 配置失效）后清缓存，让 OkHttp 的重试换一份配置。
     * 活源那份也一起丢（它可能正是被拒的那份），并把这个域名翻成「用它自己的记录」，
     * 否则重试会拿回同一个值、一直撞同一堵墙。
     */
    fun invalidateEch(host: String) {
        echCache.remove(host)
        echFailed.remove(host)
        echCache.remove(LIVE_SOURCE_HOST)
        if (host != LIVE_SOURCE_HOST) ownFirst.add(host)
    }

    // ---------------- DNS ----------------

    /** 用 DoH 解析。失败返回空列表；调用方必须 fail-closed，不要回落系统 DNS（会拿污染 IP） */
    fun resolve(host: String): List<InetAddress> {
        val r = resolver() ?: return emptyList()
        return try {
            val addrs = r.lookup(host)
            Log.i(TAG, "doh resolve $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}")
            addrs
        } catch (t: Throwable) {
            Log.w(TAG, "doh resolve failed for $host: ${t.message}")
            emptyList()
        }
    }

    /** 底层 JSON 查询（仅用于 HTTPS(65) 记录；A/AAAA 交给 DnsOverHttps） */
    private fun query(dohUrl: String, host: String, type: String): String? {
        val req = Request.Builder()
            .url("$dohUrl?name=$host&type=$type")
            .header("Accept", "application/dns-json")
            .build()
        bootstrapClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "doh $type HTTP ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }
}

/**
 * 受保护域名走 DoH（系统 DNS 在大陆被污染，连到假 IP 会得出错误结论）；
 * 其余域名保持 App 原有解析策略 [fallback]（不改动非 ECH 链路的行为）。
 *
 * 解析失败即抛异常：fail-closed，**不回落系统 DNS**。
 */
class EchDns(private val fallback: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!EchHosts.isProtected(hostname)) return fallback.lookup(hostname)
        val addrs = EchDoh.resolve(hostname)
        if (addrs.isEmpty()) {
            throw UnknownHostException("DoH 解析失败（fail-closed）：$hostname")
        }
        return addrs
    }
}
