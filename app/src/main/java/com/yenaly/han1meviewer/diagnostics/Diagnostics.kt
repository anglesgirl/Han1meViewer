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
        installCrashReporter()
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

    /**
     * Reports fatal crashes synchronously before the process dies, then delegates to the
     * previously installed handler (CrashX) so existing behaviour is preserved.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTrace.take(12).joinToString("\n") { it.toString() }
                val fields = mapOf(
                    "thread" to thread.name,
                    "error_type" to error.javaClass.name,
                    "message" to (error.message ?: "unknown"),
                    "stack" to trace,
                    "version_name" to BuildConfig.VERSION_NAME,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                )
                // Never touch the network on the crashing thread: it may be the main thread,
                // where Android forbids network access. Upload on a worker and wait briefly.
                val worker = Thread { runCatching { uploadBlocking("app_crash", fields) } }
                worker.isDaemon = true
                worker.start()
                worker.join(crashUploadTimeoutMs)
            }
            previous?.uncaughtException(thread, error)
        }
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
        uploadBlocking(name, fields)
    }

    /** Performs the HTTP POST on the calling thread; used by the crash handler. */
    private fun uploadBlocking(name: String, fields: Map<String, String>) {
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
    private const val crashUploadTimeoutMs = 4_000L
    private val sensitiveKeys = setOf(
        "authorization", "cookie", "cookies", "token", "password", "secret",
        "request_body", "response_body", "url", "full_url",
    )
}
