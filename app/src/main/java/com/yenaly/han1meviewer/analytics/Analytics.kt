package com.yenaly.han1meviewer.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.yenaly.han1meviewer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object Analytics {
    private const val ENDPOINT = "https://analytics.anglesgirl.eu.org/api/event"
    private const val SITE_BASE = "https://han1meviewer.anglesgirl.eu.org/app"
    private const val PREF = "mh_analytics"
    private const val KEY_SESSION = "_mh_uid"
    private const val SESSION_DURATION = 30 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        trackAppOpen()
    }

    fun trackAppOpen() {
        // APP 样子：版本号、系统、机型、语言、屏幕
        val version = BuildConfig.VERSION_NAME
        val os = Build.VERSION.SDK_INT
        val model = Build.MODEL
        val lang = Locale.getDefault().toLanguageTag()
        val referrer = "version:$version os:$os model:$model lang:$lang"
        track("open", referrer)
        // 额外按版本维度发一遍，方便看板按版本分组
        track("version/${sanitize(version)}", referrer)
    }

    fun track(event: String, referrer: String = "") {
        val p = prefs ?: return
        val ctx = appContext
        scope.launch { runCatching { send(event, referrer, p, ctx) } }
    }

    private fun send(event: String, referrer: String, prefs: SharedPreferences, context: Context?) {
        val sessionId = getSessionId(prefs)
        val width = screenWidth(context)
        val language = Locale.getDefault().language
        val version = BuildConfig.VERSION_NAME
        // url 带版本，MHAnalytics 会按 url 分组，天然就是版本统计
        val url = when (event) {
            "open" -> "$SITE_BASE?v=${enc(version)}&os=${Build.VERSION.SDK_INT}"
            else -> "$SITE_BASE/$event?v=${enc(version)}"
        }
        val body = JSONObject().apply {
            put("url", url)
            put("referrer", referrer)
            put("sessionId", sessionId)
            put("width", width)
            put("language", language)
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Content-Length", body.size.toString())
        }
        try {
            conn.outputStream.use { it.write(body) }
            conn.inputStream.close()
        } catch (_: Exception) {
            try { conn.errorStream?.close() } catch (_: Exception) {}
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(v: String) = try { URLEncoder.encode(v, "UTF-8") } catch (_: Exception) { v }
    private fun sanitize(v: String) = v.replace(Regex("[^a-zA-Z0-9._-]"), "-")

    private fun getSessionId(prefs: SharedPreferences): String {
        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_SESSION, null)
        if (raw != null) {
            try {
                val obj = JSONObject(raw)
                val id = obj.optString("id")
                val last = obj.optLong("lastActive", 0)
                if (id.isNotEmpty() && now - last < SESSION_DURATION) {
                    prefs.edit().putString(KEY_SESSION, JSONObject().apply { put("id", id); put("lastActive", now) }.toString()).apply()
                    return id
                }
            } catch (_: Exception) {}
        }
        val id = java.util.UUID.randomUUID().toString().replace("-","").substring(0,8) + java.lang.Long.toString(now, 36)
        prefs.edit().putString(KEY_SESSION, JSONObject().apply { put("id", id); put("lastActive", now) }.toString()).apply()
        return id
    }

    private fun screenWidth(context: Context?): Int {
        return try {
            val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(metrics)
            if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        } catch (_: Exception) { 1080 }
    }
}
