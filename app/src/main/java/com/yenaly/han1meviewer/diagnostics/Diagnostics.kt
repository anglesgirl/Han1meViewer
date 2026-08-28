package com.yenaly.han1meviewer.diagnostics

import android.content.Context
import android.os.Build
import com.yenaly.han1meviewer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Independent, sanitized diagnostics transport. It must never affect application requests.
 */
object Diagnostics {
    private const val endpoint = "https://log.anglesgirl.eu.org/v1/events"
    private const val appId = "han1meviewer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        event(
            "app_started",
            mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            ),
        )
    }

    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        val safeFields = fields.mapNotNull { (key, value) ->
            if (key in sensitiveKeys || value == null) null else key to value.toString().take(maxValueLength)
        }.toMap()
        scope.launch {
            runCatching { upload(name, safeFields) }
        }
    }

    private suspend fun upload(name: String, fields: Map<String, String>) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("app", appId)
            put("event", name)
            put("timestamp", Instant.now().toString())
            put("version", BuildConfig.VERSION_NAME)
            put("fields", JSONObject(fields))
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }
        try {
            connection.outputStream.use { it.write(body) }
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private const val maxValueLength = 512
    private val sensitiveKeys = setOf(
        "authorization", "cookie", "cookies", "token", "password", "secret",
        "request_body", "response_body", "url", "full_url",
    )
}
