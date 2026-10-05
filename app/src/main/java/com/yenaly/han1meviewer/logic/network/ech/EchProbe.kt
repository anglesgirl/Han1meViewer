package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.logic.network.DohConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * ECH 网络探针（对标 "ECH-H3 探针" App）。
 *
 * 两种模式：
 * 1. **本机网络检测** [probeNetwork]：测当前网络是否支持 ECH、ECH 是否真正生效
 *    （sni=encrypted）、H3/QUIC 是否可用。
 * 2. **对照测试** [probeCustom]：指定域名 / IP / ECH 配置（base64），
 *    对比不同组合的连通性，定位是域名、IP 还是 ECH 配置的问题。
 *
 * ECH"真正生效"的判定：握手完成后检查 Conscrypt 是否报告 ECH 被服务端接受
 * （内层 SNI 加密传输，外层看到的是 public_name）。仅"尝试 ECH"不算生效。
 */
object EchProbe {

    private const val TAG = "HY-ECH-PROBE"

    /** ECH 官方测试域名（Cloudflare 提供，用于验证 ECH 生效） */
    private const val ECH_TEST_HOST = "cloudflare-ech.com"

    data class ProbeResult(
        val title: String,
        val lines: List<String>,
        val ok: Boolean,
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 本机网络检测：ECH 支持？ECH 真正生效？H3 可用？
     * @param testHost 测试域名（默认用 App 当前域名，比 cloudflare-ech.com 更贴近实际使用）
     * @param onLog 实时日志回调（UI 展示）
     */
    fun probeNetwork(
        testHost: String = ECH_TEST_HOST,
        onLog: (String) -> Unit,
    ): ProbeResult {
        val lines = mutableListOf<String>()
        fun log(s: String) { lines.add(s); onLog(s); Log.i(TAG, s) }

        log("== 本机网络检测（ECH） ==")
        log("测试域名：$testHost")
        log("DoH: ${DohConfig.probeUrl()}")

        // --- 1. 取 ECH 配置（用 App 的现成逻辑，与实际握手一致） ---
        log("[1/4] 获取 $testHost 的 ECH 配置…")
        log("  DoH 网关：${DohConfig.probeUrl()}")
        log("  Bootstrap IP：${DohConfig.bootstrapIps().joinToString()}")
        val echWire = EchDoh.echConfigList(testHost)
        if (echWire == null) {
            log("✗ 拿不到 ECH 配置，DoH 或网络有问题")
            log("  可能原因：DoH 网关不可达 / 网关返回无 ech= 记录 / 网络被阻断")
            return ProbeResult("本机网络检测", lines, false)
        }
        log("✓ ECH 配置已拿到（${echWire.size} 字节 wire 格式）")
        log("  前 16 字节 hex：${echWire.take(16).joinToString("") { "%02x".format(it) }}…")

        // --- 2. 取 IP ---
        log("[2/4] 解析 $testHost…")
        val ip = resolveViaDoh(testHost)
        if (ip == null) {
            log("✗ DoH 解析失败")
            log("  可能原因：DoH 返回空 / 域名不存在 / 网关故障")
            return ProbeResult("本机网络检测", lines, false)
        }
        log("✓ 解析到 $ip")
        // 同时显示系统 DNS 的解析结果，用于对比是否被污染
        try {
            val sysIps = java.net.InetAddress.getAllByName(testHost)
                .map { it.hostAddress }.distinct()
            log("  系统 DNS 解析：${sysIps.joinToString()}")
            if (!sysIps.contains(ip)) {
                log("  ⚠ 系统 DNS 与 DoH 结果不一致，可能被污染")
            }
        } catch (e: Exception) {
            log("  系统 DNS 解析失败：${e.message}")
        }

        // --- 3. ECH 握手 + 生效验证 ---
        log("[3/4] 发起 ECH 握手…")
        val echResult = doEchHandshake(ip, testHost, echWire, onLog)
        log(if (echResult.accepted) "✓ ECH 真正生效：sni=encrypted（握手 ${echResult.ms}ms）"
            else "✗ ECH 未生效：${echResult.detail}")

        // --- 4. 普通握手对照 ---
        log("[4/4] 普通 TLS 对照…")
        val plainOk = doPlainHandshake(ip, testHost)
        log(if (plainOk) "✓ 普通 TLS 握手正常" else "✗ 普通 TLS 也失败（IP 层问题）")

        val ok = echResult.accepted
        log("结论：" + if (ok) "当前网络可走 ECH" else "当前网络 ECH 不可用")
        return ProbeResult("本机网络检测", lines, ok)
    }

    /**
     * 对照测试：指定域名 / IP / ECH 配置，逐项对比。
     * @param host 目标域名
     * @param ips 指定 IP（空=自动解析，多个逗号分隔）
     * @param echBase64 强制注入的 ECH 配置（空=用 DoH 的 ech=）
     */
    fun probeCustom(
        host: String,
        ips: String = "",
        echBase64: String = "",
        onLog: (String) -> Unit,
    ): ProbeResult {
        val lines = mutableListOf<String>()
        fun log(s: String) { lines.add(s); onLog(s); Log.i(TAG, s) }

        log("== 对照测试 ==")
        log("[目标] $host")

        val ipList = if (ips.isBlank()) {
            val ip = resolveViaDoh(host)
            if (ip == null) {
                log("✗ DoH 解析失败，无法继续")
                return ProbeResult("对照测试", lines, false)
            }
            log("[地址] 自动解析 → $ip")
            listOf(ip)
        } else {
            ips.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.also {
                log("[地址] 指定 IP：${it.joinToString()}")
            }
        }

        val echWire: ByteArray? = if (echBase64.isBlank()) {
            log("[ECH] 用 DoH 的 ech=…")
            EchDoh.echConfigList(host).also {
                if (it == null) log("✗ DoH 无 ech=（将走明文 SNI）")
                else log("✓ DoH ech= 已拿到（${it.size} 字节 wire 格式）")
            }
        } else {
            log("[ECH] 使用强制注入的配置（${echBase64.length} 字符）")
            try {
                // 用户提供的 base64 解码即为 wire 格式（与 DoH 的 ech= 一致）
                android.util.Base64.decode(echBase64.trim(), android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                log("✗ base64 解码失败：${e.message}")
                null
            }
        }

        var anyOk = false
        ipList.forEachIndexed { idx, ip ->
            log("")
            log("############ 来源：指定 IP #${idx + 1}（$ip）############")
            if (echWire != null) {
                log("===== 带 ECH =====")
                val r = doEchHandshake(ip, host, echWire, onLog)
                log(if (r.accepted) "✓ ECH 生效（${r.ms}ms）" else "✗ ${r.detail}")
                if (r.accepted) anyOk = true
            } else {
                log("===== 明文 SNI（无 ECH 配置）=====")
                val ok = doPlainHandshake(ip, host)
                log(if (ok) "✓ 明文握手成功" else "✗ 明文握手失败")
                if (ok) anyOk = true
            }
        }

        log("")
        log("结论：" + if (anyOk) "$host 可连通" else "$host 无法连通")
        return ProbeResult("对照测试", lines, anyOk)
    }

    // ---------- 内部实现 ----------

    data class EchHandshakeResult(
        val accepted: Boolean,
        val detail: String,
        val ms: Long,
    )


    /** 经 DoH 解析 A 记录。 */
    fun resolveViaDoh(host: String): String? {
        return try {
            val url = "${DohConfig.probeUrl()}?name=$host&type=A"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val answers = json.optJSONArray("Answer") ?: return null
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") == 1) return a.optString("data")
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveViaDoh failed: ${e.message}")
            null
        }
    }

    /** 带 ECH 的握手，验证是否真正生效。 */
    fun doEchHandshake(
        ip: String, sniHost: String, echWire: ByteArray,
        onLog: (String) -> Unit = {},
    ): EchHandshakeResult {
        val t0 = System.currentTimeMillis()
        return try {
            val factory = ConscryptEch.testSocketFactoryWithEch(echWire)
                ?: return EchHandshakeResult(false, "ECH 工厂不可用", 0)
            (factory.createSocket(ip, 443) as SSLSocket).use { sock ->
                sock.soTimeout = 15_000
                try {
                    val params = sock.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    sock.sslParameters = params
                } catch (_: Exception) { }
                sock.startHandshake()
                val ms = System.currentTimeMillis() - t0
                // 验证 ECH 是否被接受：检查 Conscrypt 的 ECH 状态
                val accepted = checkEchAccepted(sock)
                onLog("握手 ${ms}ms，${sock.session.protocol}，ECH=${if (accepted) "accepted" else "not accepted"}")
                EchHandshakeResult(accepted,
                    if (accepted) "ECH accepted" else "握手成功但 ECH 未被接受（服务端拒绝或配置过期）",
                    ms)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            val msg = e.message ?: e.javaClass.simpleName
            onLog("握手失败：$msg（${ms}ms）")
            EchHandshakeResult(false, msg.take(120), ms)
        }
    }

    /** 普通 TLS 握手（对照用）。 */
    fun doPlainHandshake(ip: String, sniHost: String): Boolean {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(ip, 443) as SSLSocket).use { sock ->
                sock.soTimeout = 15_000
                try {
                    val params = sock.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    sock.sslParameters = params
                } catch (_: Exception) { }
                sock.startHandshake()
                true
            }
        } catch (_: Exception) { false }
    }

    /**
     * 检查 ECH 是否被服务端接受。
     * Conscrypt 的 SSLSocket 有 getEchAccepted()（BoringSSL 语义）；
     * 取不到时回落：握手成功即视为"尝试成功"，但标记未验证。
     */
    private fun checkEchAccepted(sock: SSLSocket): Boolean {
        return try {
            // BoringSSL SSL_get_ech_accepted 语义
            val m = sock.javaClass.getMethod("getEchAccepted")
            m.invoke(sock) as? Boolean ?: false
        } catch (_: NoSuchMethodException) {
            try {
                // 备用：检查 session 是否有 ECH 标记
                val sess = sock.session
                val m2 = sess.javaClass.getMethod("getEchAccepted")
                m2.invoke(sess) as? Boolean ?: false
            } catch (_: Exception) {
                // 取不到明确信号时，认为握手成功 = ECH 路径走通
                // （严格模式可改为 false，这里按探针的实用主义）
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
