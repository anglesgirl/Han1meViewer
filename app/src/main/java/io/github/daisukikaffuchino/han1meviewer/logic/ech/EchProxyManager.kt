package io.github.daisukikaffuchino.han1meviewer.logic.ech

import android.content.Context
import android.util.Log
import echproxy.Echproxy
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.utils.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

/**
 * ECH 代理管理器:负责启动/停止 Go ECH 代理(gomobile 编译的 echproxy AAR)。
 *
 * 代理监听 127.0.0.1:<port>,把 Hanime.tv 的请求通过 ECH TLS 握手转发,
 * 隐藏 SNI 防止被 GFW 重置。ECH 公钥配置来自 cloudflare-ech.com(缓存5h),
 * 握手失败自动兜底一次,再失败降级普通 TLS。
 *
 * 远程配置:启动时从 ech-config.anglesgirl.eu.org 的 DNS TXT 记录拉取
 * doh/doh2/doh3/ip(多 DoH 依次尝试 + 自定义边缘 IP),失败则回退到
 * 本地设置的 DoH 预设(alidns/dnspod/cloudflare),再兜底 alidns。
 *
 * 用法:
 *   EchProxyManager.start(context)   // 启动,返回本地代理端口
 *   EchProxyManager.stop()           // 停止
 *   EchProxyManager.isRunning        // 是否在运行
 */
object EchProxyManager {

    private const val TAG = "EchProxy"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = port > 0

    private var cachePath: String? = null

    /** 远端配置缓存文件(两行: doh 逗号串 / ip 串),启动时直接复用,不等网络。 */
    private var configCacheFile: File? = null

    /** 兜底 DoH 端点(仅当远程配置和本地预设都不可用时)。 */
    const val DEFAULT_DOH = "https://dns.alidns.com/dns-query"

    /** 远程配置域名(DNS TXT 下发 doh/doh2/doh3/ip)。 */
    const val REMOTE_CONFIG_DOMAIN = "ech-config.anglesgirl.eu.org"

    /** 状态轮询任务。 */
    private var statusJob: kotlinx.coroutines.Job? = null

    /**
     * 启动 ECH 代理。
     * @param context 用于定位缓存目录
     * @param doh 显式指定 DoH(调试用,可空则走远程配置/本地预设)
     * @return 本地代理端口,失败返回 -1
     */
    suspend fun start(context: Context, doh: String? = null): Int = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext port
        try {
            cachePath = File(context.filesDir, "ech-public-config.json").absolutePath
            configCacheFile = File(context.filesDir, "ech-remote-config.txt")
            val chosen = freePort()

            // 1. 立即用缓存/本地预设启动代理——不等待 remote config。
            //    远端配置(优选 DoH/边缘 IP)由后台任务刷新后热更新,首屏不等网络。
            val cached = loadConfigCache()
            val dohArg = doh
                ?: cached?.first
                ?: localPresetDoh()
                ?: DEFAULT_DOH
            val ipArg = cached?.second ?: ""
            LogUtil.record(
                "I", TAG,
                "starting ECH proxy on 127.0.0.1:$chosen (doh=$dohArg, ip=$ipArg)" +
                    (if (cached != null) " [config cache hit]" else "")
            )

            Echproxy.start(
                "127.0.0.1:$chosen",          // listen
                "hanime.tv",                  // target
                "",                           // echB64 (空 → DoH/cloudflare-ech.com + fallback)
                dohArg,                       // DoH JSON endpoint (逗号分隔多 DoH)
                ipArg,                        // ipList (远程配置的自定义边缘 IP)
                cachePath!!,                  // ECH 公钥配置缓存(5h)
                false,                        // insecure
            )
            port = chosen
            LogUtil.record("I", TAG, "ECH proxy started on 127.0.0.1:$chosen")
            // 让系统代理(HttpURLConnection/WebView)指向本地 ECH 代理。
            HProxySelector.rebuildNetwork()
            startStatusPolling()

            // 2. 后台异步刷新远端配置:拉到新配置 → 写缓存 → 热更新 Go 端端点。
            scope.launch { refreshRemoteConfig(dohArg, ipArg) }
            chosen
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy start failed", e)
            LogUtil.record("E", TAG, "ECH proxy start failed: ${e.message}")
            port = -1
            -1
        }
    }

    /**
     * 后台刷新远端配置(不阻塞启动)。
     * 成功:写缓存文件,若与当前端点不同则 SetEndpoints 热更新(无需重启代理)。
     * 失败:沿用缓存/当前配置,仅记录。
     */
    private suspend fun refreshRemoteConfig(currentDoh: String, currentIp: String) {
        runCatching { fetchRemoteConfig() }
            .onSuccess { cfg ->
                val list = listOfNotNull(cfg.doh, cfg.doh2, cfg.doh3).distinct()
                val newDoh = if (list.isNotEmpty()) list.joinToString(",") else null
                val newIp = cfg.ip?.takeIf { it.isNotBlank() }
                LogUtil.record("I", TAG, "remote config: doh=$newDoh, ip=$newIp")
                saveConfigCache(newDoh, newIp)
                if (newDoh != null || newIp != null) {
                    val finalDoh = newDoh ?: currentDoh
                    val finalIp = newIp ?: ""
                    if (finalDoh != currentDoh || finalIp != currentIp) {
                        runCatching { Echproxy.setEndpoints(finalDoh, finalIp) }
                            .onSuccess {
                                LogUtil.record("I", TAG, "endpoints hot-updated (doh=$finalDoh, ip=$finalIp)")
                            }
                            .onFailure { e ->
                                LogUtil.record("W", TAG, "endpoints hot-update failed: ${e.message}")
                            }
                    }
                }
            }
            .onFailure { e ->
                LogUtil.record("W", TAG, "remote config refresh failed (using cached/current): ${e.message}")
            }
    }

    /** 本地 DoH 预设(用户设置),remote config 与显式参数都缺失时的回退。 */
    private fun localPresetDoh(): String? = runCatching {
        val cfg = io.github.daisukikaffuchino.han1meviewer.logic.network.DohConfig
        when (SettingsRepository.dohPreset) {
            "custom" -> cfg.customUrl().takeIf { it.isNotBlank() }
            else -> cfg.selectedPreset().url
        }
    }.getOrNull()

    /** 读取上次成功的远端配置缓存(两行: doh 逗号串 / ip 串)。 */
    private fun loadConfigCache(): Pair<String, String>? {
        val f = configCacheFile ?: return null
        return runCatching {
            val lines = f.readLines()
            if (lines.isEmpty() || lines[0].isBlank()) null
            else lines[0] to (lines.getOrNull(1) ?: "")
        }.getOrNull()
    }

    private fun saveConfigCache(doh: String?, ip: String?) {
        val f = configCacheFile ?: return
        runCatching { f.writeText("${doh ?: ""}\n${ip ?: ""}") }
    }

    /** 每 3 秒把 ECH 代理的握手/降级状态写入日志缓冲(日志页可见)。 */
    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = scope.launch {
            var last = ""
            while (isRunning) {
                val s = try { Echproxy.lastStatus() } catch (e: Throwable) { "" }
                if (s.isNotBlank() && s != last) {
                    last = s
                    LogUtil.record("I", TAG, "status: $s")
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    /**
     * 从远程配置域名的 DNS TXT 记录拉取 doh/doh2/doh3/ip。
     * 依次尝试本地预设 DoH + 默认 alidns,解析 TXT。
     */
    private suspend fun fetchRemoteConfig(): RemoteEchConfig = withContext(Dispatchers.IO) {
        val dohCandidates = listOfNotNull(
            runCatching {
                val cfg = io.github.daisukikaffuchino.han1meviewer.logic.network.DohConfig
                when (SettingsRepository.dohPreset) {
                    "custom" -> cfg.customUrl().takeIf { it.isNotBlank() }
                    else -> cfg.selectedPreset().url
                }
            }.getOrNull(),
            DEFAULT_DOH,
        ).distinct()

        var lastError: Exception? = null
        for (doh in dohCandidates) {
            try {
                val txt = dohQuery(doh, REMOTE_CONFIG_DOMAIN, "TXT")
                val cfg = parseRemoteConfig(txt)
                if (cfg.doh != null || cfg.ip != null) {
                    return@withContext cfg
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("no DoH endpoint available")
    }

    private fun dohQuery(doh: String, name: String, type: String): String {
        val qtype = when (type) {
            "TXT" -> 16
            "A" -> 1
            "AAAA" -> 28
            "HTTPS" -> 65
            else -> throw IllegalArgumentException("unsupported qtype $type")
        }
        val query = buildDnsQuery(name, qtype)
        // 必须显式 NO_PROXY:ECH 开启时 rebuildNetwork 会把系统代理设为
        // 本地 ECH 代理,HttpURLConnection 默认读系统属性 → 请求走 CONNECT
        // 隧道 → 递归/失败。远程配置查询应直连 DoH 服务器。
        val conn = URL(doh).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/dns-message")
        conn.setRequestProperty("accept", "application/dns-message")
        conn.setRequestProperty("User-Agent", "Han1meViewer")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.outputStream.use { it.write(query) }
        val code = conn.responseCode
        if (code != 200) throw Exception("DoH HTTP $code via $doh")
        val body = conn.inputStream.use { it.readBytes() }
        return parseDnsResponse(body)
    }

    /** 构造 RFC 8484 二进制 DNS 查询报文(单问题,无 EDNS)。 */
    private fun buildDnsQuery(name: String, qtype: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        // Header: ID=0x1234, Flags=0x0100(RD), QDCOUNT=1, AN/NS/AR=0
        buf.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        // QNAME
        name.trimEnd('.').split(".").filter { it.isNotEmpty() }.forEach { label ->
            buf.write(label.length)
            buf.write(label.toByteArray(Charsets.UTF_8))
        }
        buf.write(0)
        // QTYPE + QCLASS(IN)
        buf.write((qtype shr 8) and 0xFF)
        buf.write(qtype and 0xFF)
        buf.write(0)
        buf.write(1)
        return buf.toByteArray()
    }

    /** 解析 RFC 8484 响应,返回 TXT 记录(每条一行,chunk 用引号包裹)。 */
    private fun parseDnsResponse(msg: ByteArray): String {
        if (msg.size < 12) throw Exception("truncated DNS header")
        val rcode = msg[3].toInt() and 0x0F
        if (rcode != 0) throw Exception("DoH DNS status $rcode")
        val qdCount = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        val anCount = ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)
        var pos = 12
        // 跳过 question 区
        repeat(qdCount) {
            pos = skipDnsName(msg, pos)
            pos += 4 // QTYPE + QCLASS
        }
        val lines = mutableListOf<String>()
        repeat(anCount) {
            pos = skipDnsName(msg, pos)
            if (pos + 10 > msg.size) throw Exception("truncated answer header")
            val atype = ((msg[pos].toInt() and 0xFF) shl 8) or (msg[pos + 1].toInt() and 0xFF)
            val rdLen = ((msg[pos + 8].toInt() and 0xFF) shl 8) or (msg[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLen > msg.size) throw Exception("truncated rdata")
            val rdata = msg.copyOfRange(pos, pos + rdLen)
            pos += rdLen
            if (atype == 16) { // TXT
                val parts = mutableListOf<String>()
                var p = 0
                while (p < rdata.size) {
                    val l = rdata[p].toInt() and 0xFF
                    p++
                    if (p + l > rdata.size) break
                    parts.add(String(rdata, p, l, Charsets.UTF_8))
                    p += l
                }
                // 直接拼接 chunk 原文,不加引号:parseRemoteConfig 按 `;`/`=`
                // 解析 key,若带引号包裹会导致首字段 key 变 `"ip` 匹配失败。
                if (parts.isNotEmpty()) lines.add(parts.joinToString(""))
            }
        }
        if (lines.isEmpty()) throw Exception("no TXT records in DoH response")
        return lines.joinToString("\n")
    }

    /** 跳过 DNS name(支持压缩指针),返回下一个字段的偏移。 */
    private fun skipDnsName(msg: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            if (p >= msg.size) throw Exception("truncated DNS name")
            val b = msg[p].toInt() and 0xFF
            if (b == 0) return p + 1
            if (b and 0xC0 == 0xC0) return p + 2 // 压缩指针
            p += 1 + b
        }
    }

    private fun parseRemoteConfig(txt: String): RemoteEchConfig {
        val cfg = RemoteEchConfig()
        txt.split("\n").forEach { line ->
            line.split(";").forEach { part ->
                val idx = part.indexOf("=")
                if (idx > 0) {
                    val key = part.substring(0, idx).trim().lowercase()
                    val value = part.substring(idx + 1).trim().trim('"')
                    when (key) {
                        "doh" -> cfg.doh = value
                        "doh2" -> cfg.doh2 = value
                        "doh3" -> cfg.doh3 = value
                        "ip", "ips" -> cfg.ip = value
                    }
                }
            }
        }
        return cfg
    }

    /** 停止 ECH 代理。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext
        try {
            Echproxy.stop()
            Log.i(TAG, "ECH proxy stopped")
            LogUtil.record("I", TAG, "ECH proxy stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "ECH proxy stop failed", e)
            LogUtil.record("E", TAG, "ECH proxy stop failed: ${e.message}")
        } finally {
            port = -1
            // 恢复系统代理到用户原配置。
            io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector.rebuildNetwork()
        }
    }

    /** 启动(非挂起版本,供 Application 使用)。 */
    fun startAsync(context: Context, doh: String? = null) {
        scope.launch { start(context, doh) }
    }

    /** 停止(非挂起版本)。 */
    fun stopAsync() {
        scope.launch { stop() }
    }

    /** 获取最近的状态摘要(供调试页/设置页显示)。 */
    fun status(): String = try {
        Echproxy.lastStatus()
    } catch (e: Throwable) {
        "status unavailable: ${e.message}"
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private data class RemoteEchConfig(
        var doh: String? = null,
        var doh2: String? = null,
        var doh3: String? = null,
        var ip: String? = null,
    )
}
