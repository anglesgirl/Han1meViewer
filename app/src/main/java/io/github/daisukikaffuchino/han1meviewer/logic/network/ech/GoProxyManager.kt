package io.github.daisukikaffuchino.han1meviewer.logic.network.ech

import android.util.Log
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.utils.applicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the lifecycle of the Go ECH proxy binary.
 *
 * The Go binary is bundled as libechproxy.so in jniLibs and extracted by Android
 * to nativeLibraryDir at install time (android:extractNativeLibs="true").
 *
 * Logs and status are exposed as StateFlow for UI observation.
 */
object GoProxyManager {

    private const val TAG = "GoProxy"
    private const val BINARY_NAME = "libechproxy.so"

    /** Fixed port — matches Go proxy's DefaultPort */
    const val FIXED_PORT = 18423

    private const val DOH_URL = "https://0kbpekmcr1.cloudflare-gateway.com/dns-query"

    /**
     * ECH-enabled domains for pre-fetching at startup.
     * Derived from HanimeConstants so any domain added there automatically
     * gets ECH pre-fetching. The Go proxy also tries ECH for any domain
     * at runtime, even if not in this list.
     */
    private val ECH_DOMAINS: String by lazy {
        HanimeConstants.HANIME_HOSTNAME.joinToString(",")
    }

    @Volatile private var process: Process? = null
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    // ---------------------------------------------------------------------------
    // UI-facing state
    // ---------------------------------------------------------------------------

    private val MAX_LOG_LINES = 500

    data class ProxyStatus(
        val running: Boolean,
        val port: Int,
        val dohUrl: String,
        val binaryPath: String,
        val startTime: Long,
    )

    private val _status = MutableStateFlow(
        ProxyStatus(false, FIXED_PORT, DOH_URL, "", 0L)
    )
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val logBuffer = mutableListOf<String>()

    /** Log file for persistent storage (survives crashes) */
    @Volatile private var logFile: File? = null
    @Volatile private var logWriter: OutputStreamWriter? = null

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Creates a new log file with timestamp in app's filesDir.
     * Called at the start of each proxy session.
     */
    private fun initLogFile() {
        try {
            // Close previous writer if open
            logWriter?.close()
            val dir = File(applicationContext.filesDir, "ech-proxy-logs")
            if (!dir.exists()) dir.mkdirs()
            // Clean up old log files (keep last 5)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val file = File(dir, "echproxy_${sdf.format(Date())}.log")
            logFile = file
            logWriter = OutputStreamWriter(FileOutputStream(file, true))
            logWriter?.write("=== ECH Proxy Log Session ${Date()} ===\n")
            logWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init log file: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        val timestamp = logTimeFormat.format(Date())
        val logLine = "[$timestamp] $line"
        synchronized(logBuffer) {
            logBuffer.add(logLine)
            if (logBuffer.size > MAX_LOG_LINES) {
                logBuffer.removeAt(0)
            }
            _logs.value = logBuffer.toList()
        }
        // Write to persistent file
        try {
            logWriter?.write("$logLine\n")
            logWriter?.flush()
        } catch (e: Exception) {
            // Ignore file write errors
        }
        Log.i(TAG, "[GoProxy] $logLine")
    }

    private fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _logs.value = emptyList()
        }
    }

    /**
     * Returns all logs as a single string (for copy/share).
     */
    fun getLogsText(): String {
        return synchronized(logBuffer) {
            logBuffer.joinToString("\n")
        }
    }

    /**
     * Returns the current log file, or the most recent log file if no active session.
     */
    fun getLogFile(): File? {
        logFile?.let { if (it.exists()) return it }
        // Find most recent log file from previous sessions
        val dir = File(applicationContext.filesDir, "ech-proxy-logs")
        if (dir.exists()) {
            return dir.listFiles()?.maxByOrNull { it.lastModified() }?.takeIf { it.exists() }
        }
        return null
    }

    /**
     * Returns all log files, sorted by most recent first.
     */
    fun getAllLogFiles(): List<File> {
        val dir = File(applicationContext.filesDir, "ech-proxy-logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Reads the full content of a log file.
     */
    fun readLogFile(file: File): String {
        return if (file.exists()) file.readText() else ""
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Asynchronously starts the Go ECH proxy subprocess.
     * Safe to call from the main thread.
     */
    fun startAsync() {
        if (running.get() || starting.get()) return
        Thread {
            start()
        }.apply {
            isDaemon = true
            name = "EchProxy-Init"
            start()
        }
    }

    /**
     * Starts the Go ECH proxy subprocess (blocking).
     * Call from a background thread, or use [startAsync] from the main thread.
     */
    fun start() {
        if (running.get()) return
        if (!starting.compareAndSet(false, true)) {
            var attempts = 0
            while (!running.get() && starting.get() && attempts < 50) {
                Thread.sleep(100)
                attempts++
            }
            return
        }

        try {
            // Initialize persistent log file
            initLogFile()

            // nativeLibraryDir 下的文件是可执行的 (android:extractNativeLibs="true")
            val nativeLibDir = applicationContext.applicationInfo.nativeLibraryDir
            val binary = File(nativeLibDir, BINARY_NAME)
            val binPath = binary.absolutePath

            _status.value = _status.value.copy(binaryPath = binPath)

            if (!binary.exists()) {
                appendLog("ERROR: Binary not found: $binPath")
                appendLog("nativeLibraryDir: $nativeLibDir")
                // 列出目录下的文件帮助调试
                val dir = File(nativeLibDir)
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles()
                    if (files != null && files.isNotEmpty()) {
                        appendLog("Files in nativeLibraryDir:")
                        files.forEach { f -> appendLog("  ${f.name} (${f.length()} bytes)") }
                    } else {
                        appendLog("nativeLibraryDir is empty")
                    }
                }
                starting.set(false)
                return
            }

            appendLog("Binary: $binPath (${binary.length()} bytes)")
            if (!binary.canExecute()) {
                binary.setExecutable(true, false)
            }
            appendLog("Starting ECH proxy on port $FIXED_PORT...")
            appendLog("DoH: $DOH_URL")
            appendLog("ECH domains: $ECH_DOMAINS")

            val pb = ProcessBuilder(
                binPath,
                "-port", FIXED_PORT.toString(),
                "-doh", DOH_URL,
                "-ech-domains", ECH_DOMAINS
            )
            pb.redirectErrorStream(true)

            process = pb.start()

            // Read combined stdout+stderr in background
            Thread {
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isNotBlank()) {
                        appendLog(line!!)
                    }
                }
            }.apply {
                isDaemon = true
                name = "GoProxy-LogReader"
                start()
            }

            // Monitor process exit
            Thread {
                val code = process!!.waitFor()
                running.set(false)
                _status.value = _status.value.copy(running = false)
                appendLog("ECH proxy exited (code=$code)")
            }.apply {
                isDaemon = true
                name = "GoProxy-Watch"
                start()
            }

            // Retry health checks with increasing delays.
            // The Go proxy needs time to: bind socket, load CA certs, init DNS resolver,
            // and start the HTTP server. On slower devices this can take 1-2 seconds.
            val maxRetries = 6
            val retryDelays = longArrayOf(500, 800, 1000, 1000, 1500, 2000)
            var healthy = false

            for (i in 0 until maxRetries) {
                Thread.sleep(retryDelays[i])
                if (checkHealth()) {
                    healthy = true
                    break
                }
                val alive = process?.isAlive == true
                appendLog("Health check ${i + 1}/$maxRetries failed (process alive: $alive)")
                if (!alive) {
                    appendLog("ERROR: Process died unexpectedly")
                    break
                }
            }

            if (healthy) {
                running.set(true)
                starting.set(false)
                _status.value = _status.value.copy(running = true, startTime = System.currentTimeMillis())
                appendLog("ECH proxy started successfully on 127.0.0.1:$FIXED_PORT")
                // Ensure all known domains have ECH configs pre-fetched.
                // The Go proxy already pre-fetches via -ech-domains flag, but this
                // acts as a safety net in case the initial pre-fetch failed.
                prefetchECHDomains()
            } else if (process?.isAlive == true) {
                // Process is still alive but health check didn't pass.
                // Keep it running — the proxy may still become responsive
                // (e.g. slow DoH resolution for ECH pre-fetch blocking the server).
                appendLog("WARNING: Health check failed but process is alive. Keeping proxy running.")
                running.set(true)
                starting.set(false)
                _status.value = _status.value.copy(running = true, startTime = System.currentTimeMillis())
            } else {
                appendLog("ERROR: Health check failed and process is not running")
                process?.destroyForcibly()
                process = null
                starting.set(false)
            }

        } catch (e: Exception) {
            appendLog("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            if (e.cause != null) {
                appendLog("  Caused by: ${e.cause!!.javaClass.simpleName}: ${e.cause!!.message}")
            }
            running.set(false)
            starting.set(false)
        }
    }

    /**
     * Stops the Go ECH proxy subprocess.
     */
    fun stop() {
        appendLog("Stopping ECH proxy...")
        running.set(false)
        starting.set(false)
        process?.destroyForcibly()
        process = null
        _status.value = _status.value.copy(running = false)
        appendLog("ECH proxy stopped")
    }

    /**
     * Restarts the Go ECH proxy subprocess.
     */
    fun restart() {
        stop()
        Thread.sleep(500)
        clearLogs()
        start()
    }

    /**
     * Clears the log buffer.
     */
    fun clearLog() = clearLogs()

    /**
     * Checks if the proxy is healthy by hitting /health endpoint.
     *
     * Uses [Proxy.NO_PROXY] explicitly to bypass any JVM-level proxy settings
     * (e.g. system properties set by HProxySelector.rebuildNetwork) that would
     * route the health check through the proxy itself, creating an infinite loop.
     */
    private fun checkHealth(): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:$FIXED_PORT/health")
            val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            appendLog("Health check error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun isRunning(): Boolean = running.get() && process?.isAlive == true

    fun getPort(): Int = FIXED_PORT

    val httpProxy: Proxy by lazy {
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", FIXED_PORT))
    }

    fun proxyUrl(originalUrl: String): String {
        val encoded = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$FIXED_PORT/proxy?url=$encoded"
    }

    /**
     * Pre-fetches ECH configs for all known domains after the proxy starts.
     * This is a safety net — the Go proxy already pre-fetches via -ech-domains,
     * but this ensures configs are loaded even if the initial pre-fetch failed.
     */
    private fun prefetchECHDomains() {
        HanimeConstants.HANIME_HOSTNAME.forEach { domain ->
            addECHDomain(domain)
        }
    }

    /**
     * Dynamically adds an ECH domain to the Go proxy at runtime.
     * The proxy will pre-fetch ECH config for this domain in the background.
     * Safe to call from any thread; returns immediately (HTTP call is async).
     */
    fun addECHDomain(domain: String) {
        if (!running.get()) return
        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:$FIXED_PORT/add-ech-domain?domain=$domain")
                val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    appendLog("Added ECH domain: $domain")
                } else {
                    appendLog("Failed to add ECH domain $domain: HTTP $code")
                }
            } catch (e: Exception) {
                appendLog("addECHDomain error for $domain: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "EchProxy-AddDomain"
            start()
        }
    }
}
