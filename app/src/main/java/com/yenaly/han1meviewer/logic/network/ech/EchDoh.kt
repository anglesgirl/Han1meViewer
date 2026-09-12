package com.yenaly.han1meviewer.logic.network.ech

import android.util.Base64
import android.util.Log
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
    private fun resolver(): Dns? {
        val url = DohConfig.resolveUrl() ?: return null   // null = 用户没配/关掉了 DoH
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

    /**
     * 取 ECHConfigList（RFC 9460 wire 格式，**已含 2 字节长度前缀**，可直接喂 Conscrypt）。
     * @return null 表示该域名没有 ECH 配置或 DoH 拿不到 —— 调用方据此 fail-closed
     */
    fun echConfigList(host: String): ByteArray? {
        val now = System.currentTimeMillis()
        echCache[host]?.let { if (it.expireAt > now) return it.wire }
        val failedAt = echFailed[host]
        if (failedAt != null && now - failedAt < FAIL_COOLDOWN_MS) return null

        val url = DohConfig.resolveUrl()
        if (url == null) {
            Log.w(TAG, "DoH 未配置，无法获取 ECH 配置（fail-closed）：$host")
            echFailed[host] = now
            return null
        }

        return try {
            val body = query(url, host, "HTTPS") ?: run {
                echFailed[host] = now
                return null
            }
            val b64 = Regex("ech=([A-Za-z0-9+/=]+)").find(body)?.groupValues?.get(1)
            if (b64 == null) {
                Log.i(TAG, "no ech config for $host")
                echFailed[host] = now
                return null
            }
            val ttl = Regex("\"TTL\"\\s*:\\s*(\\d+)").findAll(body)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .minOrNull() ?: 300L
            // ⚠️ 解码结果就是完整 wire（含长度前缀），不要再自己加一层
            val wire = Base64.decode(b64, Base64.DEFAULT)
            echCache[host] = EchEntry(wire, now + (ttl * 1000).coerceIn(MIN_TTL_MS, MAX_TTL_MS))
            echFailed.remove(host)
            Log.i(TAG, "ech config for $host: ${wire.size} bytes, ttl=${ttl}s")
            wire
        } catch (t: Throwable) {
            Log.w(TAG, "ech query failed for $host: ${t.message}")
            echFailed[host] = now
            null
        }
    }

    /** ECH 被服务器拒绝（密钥轮换）后清缓存，下次用服务器给的 retryConfigs 重试 */
    fun invalidateEch(host: String) {
        echCache.remove(host)
        echFailed.remove(host)
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
