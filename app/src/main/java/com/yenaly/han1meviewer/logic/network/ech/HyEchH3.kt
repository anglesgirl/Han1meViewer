package com.yenaly.han1meviewer.logic.network.ech

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * quiche（HTTP/3 + ECH）的 JNI 入口。Rust 工程在 `native-h3/`，CI 用 cargo-ndk 编成 `libhn1_h3.so`。
 *
 * 移植自已验证的 CO3 `CoEchH3`（同一套 quiche 0.22 + BoringSSL ECH 补丁），**逻辑保持一致**：
 * 定位是**只服务可缓存的静态匿名 GET**（图片/样式/脚本/字体）。有状态请求（登录/POST/Cookie）
 * 一律仍走 OkHttp + Conscrypt 的 TCP/ECH 链路 —— 那条也是这里失败时的兜底。
 *
 * 策略（用户定调）：**默认所有域名都先试 H3**；失败一次就把该域名记入**负缓存**
 * （落盘 `ech_h3_state`，24h），期间直接走 H2/TCP+ECH，不再白试；成功后清掉负缓存，
 * TTL 过期会再试一次（服务端可能后来才启用 H3）。
 *
 * 实测事实：`javchu.com` 支持 H3（真机探针 3/3 成功）；`hanime1.me` 不支持 H3（握手被切）。
 * 所以「默认都试 + 失败记负缓存」正好覆盖这两种情况。
 */
object HyEchH3 {

    private const val TAG = "HY-ECH-H3"

    /** native 库名 —— 必须与 native-h3/Cargo.toml 的 `[lib] name` 一致 */
    private const val LIB_NAME = "hn1_h3"

    @Volatile
    private var loaded = false

    /** 最近一次 JNI 返回的 JSON（失败时用来定位原因） */
    @Volatile
    var lastJson: String = ""
        private set

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary(LIB_NAME)
            loaded = true
            true
        } catch (t: Throwable) {
            EchTrace.event("H3 native 库未加载：${t.message}")
            false
        }
    }

    // ---------------- H3 可用性记忆（落盘） ----------------

    private const val H3_STATE_PREFS = "ech_h3_state"

    /** 负缓存时长：服务端可能后来才启用 H3，过期后自动重试一次。 */
    private const val H3_FAIL_TTL_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var appContext: Context? = null

    /** 在 [com.yenaly.han1meviewer.HanimeApplication.onCreate] 调用一次。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context? = appContext

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(H3_STATE_PREFS, Context.MODE_PRIVATE)

    /**
     * H3 只接管「静态、不带会话」的 GET：HTML / POST / Cookie 相关请求必须走 TCP+ECH，
     * 因为 H3 这条通路**不发送 Cookie**（会把已登录状态读成未登录），
     * 且 native 侧只回 `status/body_len`，**没有响应头**（无 Set-Cookie / Content-Type）。
     *
     * 判据与 WebView 侧共用（单一事实来源），避免两边口径漂移。
     */
    fun isStaticAsset(url: okhttp3.HttpUrl): Boolean {
        val ext = url.pathSegments.lastOrNull()
            ?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext.isNotEmpty() && ext in STATIC_EXT
    }

    private val STATIC_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "ico", "svg",
        "css", "js", "mjs", "woff", "woff2", "ttf",
    )

    /** 扩展名 → MIME（H3 通路拿不到响应头，得自己补一个） */
    fun mimeFor(url: okhttp3.HttpUrl): String =
        when (url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

    /** 是否该先试 H3：只要没被负缓存拦下就算可用（不写死白名单）。 */
    fun shouldTryH3(host: String): Boolean {
        val context = ctx() ?: return false
        val until = runCatching { prefs(context).getLong("bad:$host", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() >= until
    }

    private fun rememberH3(context: Context, host: String, ok: Boolean, why: String = "") {
        runCatching {
            prefs(context).edit()
                .putLong("bad:$host", if (ok) 0L else System.currentTimeMillis() + H3_FAIL_TTL_MS)
                .apply()
        }
        if (ok) EchTrace.event("H3 可用，已记住: $host")
        else EchTrace.event("H3 不通，已记负缓存 ${H3_FAIL_TTL_MS / 3600000}h，改走 H2: $host ($why)")
    }

    /** 系统 CA 导出成单个 PEM（Rust 侧读它做校验），只做一次。 */
    fun caBundlePath(context: Context): String {
        val f = File(context.cacheDir, "han1me-system-ca.pem")
        if (f.exists() && f.length() > 1024) return f.absolutePath
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val sb = StringBuilder()
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val a = aliases.nextElement()
                val cert = ks.getCertificate(a) as? X509Certificate ?: continue
                sb.append("-----BEGIN CERTIFICATE-----\n")
                sb.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                sb.append("\n-----END CERTIFICATE-----\n")
            }
            f.writeText(sb.toString())
            f.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "系统 CA 导出失败：${t.message}")
            ""
        }
    }

    /** 走 H3+ECH 拉一个资源并落盘；任何失败返回 null（调用方回落 TCP/ECH，fail-closed）。 */
    private fun fetchToFile(
        context: Context,
        host: String,
        ip: String,
        ech: ByteArray?,
        pathWithQuery: String,
        referer: String?,
        out: File,
    ): File? {
        if (!ensureLoaded()) return null
        val echB64 = ech?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        val json = try {
            h3Fetch(host, ip, echB64, pathWithQuery, referer ?: "", caBundlePath(context), out.absolutePath)
        } catch (t: Throwable) {
            EchTrace.event("H3 调用异常：${t.message}")
            return null
        }
        val saved = try {
            lastJson = json
            JSONObject(json).optString("saved_to", "")
        } catch (_: Throwable) {
            ""
        }
        return if (saved.isNotEmpty() && !saved.startsWith("ERR:") && out.exists() && out.length() > 0) out else null
    }

    /**
     * 对外入口：受保护域名走 H3+ECH 拉取并落盘。
     * 非 H3 适用（负缓存命中）/ 解析失败 / 握手失败一律返回 null —— 调用方走 H2(TCP+ECH)。
     */
    fun fetchResourceToFile(url: String): File? {
        val context = ctx() ?: return null
        val uri = try {
            java.net.URI(url)
        } catch (_: Throwable) {
            return null
        }
        val host = uri.host ?: return null
        if (!shouldTryH3(host)) return null

        val ip = runCatching { EchDoh.resolve(host).firstOrNull()?.hostAddress }.getOrNull()
            ?: run { rememberH3(context, host, false, "DoH 未解析出 IP"); return null }
        val ech = runCatching { EchDoh.echConfigList(host) }.getOrNull()
        val pathWithQuery = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val ext = (uri.rawPath ?: "").substringAfterLast('.', "").take(5)
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "bin" }
        val out = File(context.cacheDir, "h3-" + System.nanoTime() + "." + ext)
        val ok = fetchToFile(context, host, ip, ech, pathWithQuery, null, out)
        if (ok == null) {
            rememberH3(context, host, false, "取回失败（ech=${ech?.size ?: 0}B, ip=$ip）")
            out.delete()
            return null
        }
        rememberH3(context, host, true)
        return ok
    }

    external fun h3Fetch(
        host: String,
        peerIp: String,
        echB64: String,
        path: String,
        referer: String,
        caPath: String,
        outFile: String,
    ): String
}
