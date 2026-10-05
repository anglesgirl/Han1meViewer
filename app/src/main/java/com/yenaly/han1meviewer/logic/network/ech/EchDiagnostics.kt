package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import com.yenaly.han1meviewer.logic.network.DohConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * ECH 连通性诊断模块。
 *
 * 用途：用户反馈"连不上 ECH"时，逐层定位卡在哪一步，
 * 而不是靠猜。是 DoH 被墙、DNS 污染、IP 被封、SNI RST，还是 ECH 配置过期。
 *
 * 诊断步骤（按依赖顺序）：
 * 1. DOH_REACHABLE —— DoH 网关 HTTPS 可达？
 * 2. DOH_A_QUERY —— 经 DoH 能拿到目标域名的真实 A 记录？
 * 3. ECH_CONFIG —— 经 DoH 能拿到 HTTPS(65) 记录里的 ech= 配置？
 * 4. TCP_CONNECT —— 对解析出的 IP:443 TCP 建连成功？
 * 5. TLS_PLAIN —— 不带 ECH 的普通 TLS 握手成功？（隔离 ECH 层问题）
 * 6. TLS_ECH —— 带 ECH 的握手成功？服务端是否接受 ECH？
 * 7. SNI_PROBE —— 换 SNI 对比，判断是否为 SNI 定向 RST
 *
 * 每一步返回 [DiagResult]，调用方按顺序执行，某步 FAIL 则后续依赖它的步骤 SKIP。
 */
object EchDiagnostics {

    private const val TAG = "HY-ECH-DIAG"

    enum class Status { PASS, FAIL, SKIP }

    data class DiagResult(
        val step: String,
        val status: Status,
        val detail: String,
        val latencyMs: Long = -1,
        val extra: Map<String, String> = emptyMap(),
    ) {
        val ok: Boolean get() = status == Status.PASS
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 跑完整诊断。
     * @param host 目标域名，如 "javchu.com"
     * @param onStep 每完成一步回调（用于 UI 实时展示）
     * @return 全部步骤的结果
     */
    fun runAll(host: String, onStep: (DiagResult) -> Unit = {}): List<DiagResult> {
        val results = mutableListOf<DiagResult>()
        fun emit(r: DiagResult) { results.add(r); onStep(r); Log.i(TAG, "${r.step}: ${r.status} ${r.detail}") }

        // Step 1: DoH 网关可达性
        val dohUrl = DohConfig.probeUrl()
        val r1 = checkDohReachable(dohUrl)
        emit(r1)
        if (!r1.ok) {
            emit(DiagResult("DOH_A_QUERY", Status.SKIP, "DoH 不可达，跳过"))
            emit(DiagResult("ECH_CONFIG", Status.SKIP, "DoH 不可达，跳过"))
            emit(DiagResult("TCP_CONNECT", Status.SKIP, "无解析 IP，跳过"))
            emit(DiagResult("TLS_PLAIN", Status.SKIP, "无连接，跳过"))
            emit(DiagResult("TLS_ECH", Status.SKIP, "无连接，跳过"))
            emit(DiagResult("SNI_PROBE", Status.SKIP, "无连接，跳过"))
            return results
        }

        // Step 2: DoH A 记录查询
        val r2 = queryAViaDoh(dohUrl, host)
        emit(r2)
        val ip = r2.extra["ip"]
        if (!r2.ok || ip == null) {
            emit(DiagResult("ECH_CONFIG", Status.SKIP, "无 A 记录，跳过"))
            emit(DiagResult("TCP_CONNECT", Status.SKIP, "无解析 IP，跳过"))
            emit(DiagResult("TLS_PLAIN", Status.SKIP, "无连接，跳过"))
            emit(DiagResult("TLS_ECH", Status.SKIP, "无连接，跳过"))
            emit(DiagResult("SNI_PROBE", Status.SKIP, "无连接，跳过"))
            return results
        }

        // Step 3: ECH 配置获取
        val r3 = fetchEchConfigViaDoh(dohUrl, host)
        emit(r3)

        // Step 4: TCP 建连
        val r4 = checkTcpConnect(ip, 443)
        emit(r4)
        if (!r4.ok) {
            emit(DiagResult("TLS_PLAIN", Status.SKIP, "TCP 不通，跳过"))
            emit(DiagResult("TLS_ECH", Status.SKIP, "TCP 不通，跳过"))
            emit(DiagResult("SNI_PROBE", Status.SKIP, "TCP 不通，跳过"))
            return results
        }

        // Step 5: 普通 TLS 握手（不带 ECH）
        val r5 = checkTlsHandshake(ip, host, useEch = false)
        emit(r5)

        // Step 6: ECH 握手
        val echConfig = r3.extra["ech_config"]
        val r6 = if (r3.ok && echConfig != null) {
            checkTlsHandshake(ip, host, useEch = true, echConfigList = echConfig)
        } else {
            DiagResult("TLS_ECH", Status.SKIP, "无 ECH 配置，跳过")
        }
        emit(r6)

        // Step 7: SNI 探测（换 SNI 对比）
        val r7 = probeSniBlocking(ip, host)
        emit(r7)

        return results
    }

    /** 生成人类可读的诊断结论。 */
    fun summarize(results: List<DiagResult>): String {
        val byStep = results.associateBy { it.step }
        fun s(name: String) = byStep[name]

        return when {
            s("DOH_REACHABLE")?.status == Status.FAIL ->
                "DoH 网关连不上（${s("DOH_REACHABLE")?.detail}）。" +
                "可能：DoH 域名被 DNS 污染 / 网关 IP 被封。换 DoH 或检查网络。"
            s("DOH_A_QUERY")?.status == Status.FAIL ->
                "DoH 通但查不到 A 记录（${s("DOH_A_QUERY")?.detail}）。" +
                "可能：DoH 返回被干扰，换 DoH 试试。"
            s("ECH_CONFIG")?.status == Status.FAIL ->
                "拿不到 ECH 配置（${s("ECH_CONFIG")?.detail}）。" +
                "App 会 fail-closed 拒绝连接。可尝试清理缓存后重试。"
            s("TCP_CONNECT")?.status == Status.FAIL ->
                "TCP 连 ${s("TCP_CONNECT")?.extra?.get("ip")}:443 超时。" +
                "IP 层被封或路由不通，换入口 IP / 检查本地网络。"
            s("TLS_PLAIN")?.status == Status.FAIL ->
                "普通 TLS 握手都失败（${s("TLS_PLAIN")?.detail}），" +
                "问题不在 ECH——可能是 IP 被针对性 RST 或本地网络问题。"
            s("TLS_ECH")?.status == Status.FAIL ->
                "ECH 握手失败（${s("TLS_ECH")?.detail}）。" +
                "可能：ECH 配置过期 / 服务端拒绝。等配置刷新或换时间重试。"
            s("SNI_PROBE")?.status == Status.FAIL ->
                "疑似 SNI 定向阻断：换 SNI 能通、原 SNI 被 RST。"
            s("TLS_ECH")?.status == Status.PASS ->
                "ECH 全链路正常。如果 App 内仍打不开，可能是 App 层问题（非网络）。"
            else -> "诊断未完成，请看各步骤详情。"
        }
    }

    // ---------- 各步骤实现 ----------

    private fun checkDohReachable(dohUrl: String): DiagResult {
        val t0 = System.currentTimeMillis()
        return try {
            // 发一个极小的 DNS 查询验证网关 HTTPS 可达
            val url = "$dohUrl?name=example.com&type=A"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                if (resp.isSuccessful) {
                    DiagResult("DOH_REACHABLE", Status.PASS, "DoH 网关 HTTPS 可达", ms,
                        mapOf("doh_url" to dohUrl))
                } else {
                    DiagResult("DOH_REACHABLE", Status.FAIL, "HTTP ${resp.code}", ms)
                }
            }
        } catch (e: IOException) {
            DiagResult("DOH_REACHABLE", Status.FAIL, e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - t0)
        }
    }

    private fun queryAViaDoh(dohUrl: String, host: String): DiagResult {
        val t0 = System.currentTimeMillis()
        return try {
            val url = "$dohUrl?name=$host&type=A"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                if (!resp.isSuccessful) {
                    return DiagResult("DOH_A_QUERY", Status.FAIL, "HTTP ${resp.code}", ms)
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer")
                var ip: String? = null
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val a = answers.getJSONObject(i)
                        if (a.optInt("type") == 1) { ip = a.optString("data"); break }
                    }
                }
                if (ip != null) {
                    DiagResult("DOH_A_QUERY", Status.PASS, "解析到 $ip", ms, mapOf("ip" to ip))
                } else {
                    DiagResult("DOH_A_QUERY", Status.FAIL, "无 A 记录返回", ms,
                        mapOf("raw" to body.take(200)))
                }
            }
        } catch (e: Exception) {
            DiagResult("DOH_A_QUERY", Status.FAIL, e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - t0)
        }
    }

    private fun fetchEchConfigViaDoh(dohUrl: String, host: String): DiagResult {
        val t0 = System.currentTimeMillis()
        return try {
            val url = "$dohUrl?name=$host&type=HTTPS"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                if (!resp.isSuccessful) {
                    return DiagResult("ECH_CONFIG", Status.FAIL, "HTTP ${resp.code}", ms)
                }
                val body = resp.body?.string() ?: ""
                // 找 ech= 开头的配置
                val echIdx = body.indexOf("ech=")
                if (echIdx >= 0) {
                    val end = body.indexOf('"', echIdx).takeIf { it > 0 } ?: body.length
                    val ech = body.substring(echIdx, minOf(end, echIdx + 512))
                    DiagResult("ECH_CONFIG", Status.PASS, "拿到 ECH 配置 (${ech.length} 字符)", ms,
                        mapOf("ech_config" to ech))
                } else {
                    DiagResult("ECH_CONFIG", Status.FAIL, "HTTPS 记录无 ech= 字段", ms,
                        mapOf("raw" to body.take(200)))
                }
            }
        } catch (e: Exception) {
            DiagResult("ECH_CONFIG", Status.FAIL, e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - t0)
        }
    }

    private fun checkTcpConnect(ip: String, port: Int): DiagResult {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), 10_000)
                val ms = System.currentTimeMillis() - t0
                DiagResult("TCP_CONNECT", Status.PASS, "$ip:$port 建连成功", ms,
                    mapOf("ip" to ip))
            }
        } catch (e: IOException) {
            DiagResult("TCP_CONNECT", Status.FAIL, e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis() - t0, mapOf("ip" to ip))
        }
    }

    private fun checkTlsHandshake(
        ip: String, sniHost: String, useEch: Boolean, echConfigList: String? = null,
    ): DiagResult {
        val step = if (useEch) "TLS_ECH" else "TLS_PLAIN"
        val t0 = System.currentTimeMillis()
        return try {
            val factory: SSLSocketFactory = if (useEch && echConfigList != null) {
                // 用 App 的 ECH 工厂（需要 ConscryptEch 提供一个测试用的 SSLSocketFactory）
                ConscryptEch.testSocketFactoryWithEch(echConfigList)
                    ?: return DiagResult(step, Status.SKIP, "ECH 工厂不可用", System.currentTimeMillis() - t0)
            } else {
                SSLSocketFactory.getDefault() as SSLSocketFactory
            }
            (factory.createSocket(ip, 443) as SSLSocket).use { sock ->
                sock.soTimeout = 15_000
                // 设置 SNI
                try {
                    val params = sock.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
                    sock.sslParameters = params
                } catch (_: Exception) { }
                sock.startHandshake()
                val ms = System.currentTimeMillis() - t0
                val session = sock.session
                DiagResult(step, Status.PASS,
                    "握手成功 (${session.protocol}, ${session.cipherSuite})", ms)
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            // 识别常见失败模式
            val hint = when {
                msg.contains("Connection reset", true) -> "连接被 RST"
                msg.contains("timed out", true) -> "握手超时"
                msg.contains("EchRejected", true) -> "服务端拒绝 ECH（配置可能过期）"
                msg.contains("handshake", true) -> "握手失败"
                else -> msg
            }
            DiagResult(step, Status.FAIL, hint, System.currentTimeMillis() - t0,
                mapOf("raw_error" to msg.take(200)))
        }
    }

    /**
     * SNI 探测：用同一个 IP，分别以真实 SNI 和一个无害 SNI（如 example.com）握手，
     * 如果后者成功、前者被 RST，则为 SNI 定向阻断。
     */
    private fun probeSniBlocking(ip: String, realHost: String): DiagResult {
        val t0 = System.currentTimeMillis()
        fun trySni(sni: String): Boolean {
            return try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(ip, 443) as SSLSocket).use { sock ->
                    sock.soTimeout = 10_000
                    try {
                        val params = sock.sslParameters
                        params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
                        sock.sslParameters = params
                    } catch (_: Exception) { }
                    sock.startHandshake()
                    true
                }
            } catch (_: Exception) { false }
        }
        val realOk = trySni(realHost)
        val fakeOk = trySni("example.com")
        val ms = System.currentTimeMillis() - t0
        return when {
            realOk -> DiagResult("SNI_PROBE", Status.PASS, "真实 SNI 握手正常", ms)
            !realOk && fakeOk -> DiagResult("SNI_PROBE", Status.FAIL,
                "真实 SNI 被 RST、换 SNI 能通——SNI 定向阻断", ms)
            else -> DiagResult("SNI_PROBE", Status.FAIL, "两种 SNI 都失败，非 SNI 定向问题", ms)
        }
    }
}
