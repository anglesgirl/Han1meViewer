package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 通过自有 DoH 查询目标域名的 HTTPS(RR type 65) 记录，取出 SvcParam `ech`(key=5) 的
 * ECHConfigList 原始字节，供 Conscrypt 在 TLS 握手前注入。
 *
 * 设计要点：
 * - 只走应用专属 DoH 端点，不使用系统 DNS（系统 DNS 在受污染网络下不可信）。
 * - 结果按域名缓存并带 TTL；解析失败不缓存，避免把一次网络抖动固化成"无 ECH"。
 * - 该模块与业务逻辑无耦合，可独立移除。
 */
object EchConfigResolver {

    private const val TAG = "EchConfig"

    /** HTTPS 资源记录类型。 */
    private const val TYPE_HTTPS = 65

    /** SvcParamKey `ech`，承载 ECHConfigList。 */
    private const val SVC_PARAM_ECH = 5

    /** 无论上游 TTL 多长，本地最多缓存这么久，便于服务端轮换密钥后较快跟上。 */
    private val maxCacheMillis = TimeUnit.MINUTES.toMillis(30)

    /** 解析成功但记录里没有 ech 参数时的负缓存时长，避免每次连接都重查。 */
    private val negativeCacheMillis = TimeUnit.MINUTES.toMillis(5)

    private class Entry(val config: ByteArray?, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 独立的 HTTP 客户端：不能复用业务客户端，否则会与 ECH 握手形成循环依赖。 */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun clearCache() = cache.clear()

    /**
     * 取指定域名的 ECHConfigList。
     *
     * @return ECHConfigList 原始字节；域名未发布 ECH 或查询失败时返回 null。
     */
    fun configFor(dohUrl: String, host: String): ByteArray? {
        val key = host.lowercase()
        cache[key]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) return cached.config
            cache.remove(key)
        }

        val response = runCatching { queryHttpsRecord(dohUrl, key) }.getOrElse { error ->
            Log.w(TAG, "HTTPS RR query failed for $key: ${error.message}")
            Diagnostics.event(
                "ech_lookup_failure",
                mapOf(
                    "host" to key,
                    "error_type" to error.javaClass.simpleName,
                    "error" to (error.message ?: "unknown"),
                ),
            )
            return null
        }

        val parsed = runCatching { extractEchConfig(response) }.getOrElse { error ->
            Log.w(TAG, "HTTPS RR parse failed for $key: ${error.message}")
            Diagnostics.event(
                "ech_parse_failure",
                mapOf("host" to key, "error_type" to error.javaClass.simpleName),
            )
            return null
        }

        val ttlMillis = if (parsed == null) negativeCacheMillis else maxCacheMillis
        cache[key] = Entry(parsed, System.currentTimeMillis() + ttlMillis)
        Diagnostics.event(
            "ech_config_resolved",
            mapOf("host" to key, "config_len" to (parsed?.size ?: 0)),
        )
        return parsed
    }

    private fun queryHttpsRecord(dohUrl: String, host: String): ByteArray {
        val query = buildQuery(host, TYPE_HTTPS)
        val request = Request.Builder()
            .url(dohUrl)
            .post(query.toRequestBody(DNS_MESSAGE))
            .header("accept", "application/dns-message")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code}")
            return response.body.bytes()
        }
    }

    /** 构造标准 DNS 查询报文。标签串必须以 0x00 结束，否则 qtype 偏移错位。 */
    private fun buildQuery(host: String, qtype: Int): ByteArray {
        val labels = host.split('.').filter { it.isNotEmpty() }
        val out = ArrayList<Byte>(32)
        // header: id=0, flags=RD, qdcount=1
        out.addAll(listOf(0x00, 0x00, 0x01, 0x00, 0x00, 0x01).map { it.toByte() })
        out.addAll(listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size <= 63) { "DNS label too long: $label" }
            out.add(bytes.size.toByte())
            bytes.forEach(out::add)
        }
        out.add(0)
        out.add((qtype shr 8).toByte())
        out.add((qtype and 0xFF).toByte())
        out.add(0)
        out.add(1) // class IN
        return out.toByteArray()
    }

    /**
     * 从 DNS 应答里取第一条 HTTPS 记录的 ech 参数。
     *
     * @return ECHConfigList 字节；无 HTTPS 记录或记录中没有 ech 参数时返回 null。
     */
    private fun extractEchConfig(message: ByteArray): ByteArray? {
        if (message.size < 12) throw IOException("DNS response too short")
        val questionCount = readUShort(message, 4)
        val answerCount = readUShort(message, 6)
        if (answerCount == 0) return null

        var offset = 12
        repeat(questionCount) {
            offset = skipName(message, offset) + 4
        }
        repeat(answerCount) {
            offset = skipName(message, offset)
            if (offset + 10 > message.size) throw IOException("truncated RR header")
            val type = readUShort(message, offset)
            val rdLength = readUShort(message, offset + 8)
            offset += 10
            if (offset + rdLength > message.size) throw IOException("truncated RDATA")
            if (type == TYPE_HTTPS) {
                extractEchFromSvcb(message, offset, rdLength)?.let { return it }
            }
            offset += rdLength
        }
        return null
    }

    /** SVCB RDATA = SvcPriority(2) + TargetName + SvcParams(key(2)+len(2)+value)*。 */
    private fun extractEchFromSvcb(buf: ByteArray, start: Int, length: Int): ByteArray? {
        val end = start + length
        var pos = start + 2 // 跳过 SvcPriority
        pos = skipName(buf, pos)
        while (pos + 4 <= end) {
            val key = readUShort(buf, pos)
            val valueLength = readUShort(buf, pos + 2)
            pos += 4
            if (pos + valueLength > end) return null
            if (key == SVC_PARAM_ECH) {
                if (valueLength == 0) return null
                return buf.copyOfRange(pos, pos + valueLength)
            }
            pos += valueLength
        }
        return null
    }

    /** 跳过域名，兼容 0xC0 压缩指针。 */
    private fun skipName(buf: ByteArray, from: Int): Int {
        var offset = from
        while (true) {
            if (offset >= buf.size) throw IOException("truncated name")
            val length = buf[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += 1 + length
        }
    }

    private fun readUShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private val DNS_MESSAGE = "application/dns-message".toMediaType()
}
