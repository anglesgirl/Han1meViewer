package com.yenaly.han1meviewer.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object Analytics {
    private const val ENDPOINT = "https://analytics.anglesgirl.eu.org/api/event"
    private const val SITE_URL = "https://han1meviewer.anglesgirl.eu.org/app"
    private const val PREF = "mh_analytics"
    private const val KEY_SESSION = "_mh_uid"
    private const val SESSION_DURATION = 30 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        track("open")
    }

    fun track(event: String, referrer: String = "") {
        val p = prefs ?: return
        scope.launch { runCatching { send(event, referrer, p) } }
    }

    private fun send(event: String, referrer: String, prefs: SharedPreferences) {
        val sessionId = getSessionId(prefs)
        val width = screenWidth(prefs)
        val language = Locale.getDefault().language
        val url = if (event == "open") SITE_URL else "$SITE_URL/$event"
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
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36")
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

    private fun screenWidth(prefs: SharedPreferences): Int {
        return try {
            // fallback via prefs context not available, use 1080
            1080
        } catch (_: Exception) { 1080 }
    }
}
