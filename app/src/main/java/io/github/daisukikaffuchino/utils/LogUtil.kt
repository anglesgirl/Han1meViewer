package io.github.daisukikaffuchino.utils

import android.util.Log
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogUtil {
    const val DEFAULT_TAG = "Han1meViewer"

    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    /** 内存环形日志缓冲(供应用内日志查看),最多保留 [MAX_BUFFER_LINES] 行。 */
    private const val MAX_BUFFER_LINES = 2000
    private val buffer = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun v(message: String) = v(DEFAULT_TAG, message)

    fun v(tag: String, message: String) = log(tag, "V", message) { Log.v(tag, message) }

    fun d(message: String) = d(DEFAULT_TAG, message)

    fun d(tag: String, message: String) = log(tag, "D", message) { Log.d(tag, message) }

    fun i(message: String) = i(DEFAULT_TAG, message)

    fun i(tag: String, message: String) = log(tag, "I", message) { Log.i(tag, message) }

    fun w(message: String) = w(DEFAULT_TAG, message)

    fun w(message: String, throwable: Throwable?) = w(DEFAULT_TAG, message, throwable)

    fun w(tag: String, message: String) = log(tag, "W", message) { Log.w(tag, message) }

    fun w(tag: String, message: String, throwable: Throwable?) = log(tag, "W", "$message\n${throwable?.stackTraceToString() ?: ""}") {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun e(message: String) = e(DEFAULT_TAG, message)

    fun e(message: String, throwable: Throwable?) = e(DEFAULT_TAG, message, throwable)

    fun e(tag: String, message: String) = log(tag, "E", message) { Log.e(tag, message) }

    fun e(tag: String, message: String, throwable: Throwable?) = log(tag, "E", "$message\n${throwable?.stackTraceToString() ?: ""}") {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    /** 全部缓冲日志(最新在后)。 */
    fun dumpLogs(): List<String> = synchronized(buffer) { buffer.toList() }

    /** 追加一条(外部使用,如 ECH 代理状态)。 */
    fun record(level: String, tag: String, message: String) {
        append(tag, level, message)
    }

    /** 清空缓冲。 */
    fun clearLogs() {
        synchronized(buffer) { buffer.clear() }
        notifyListeners()
    }

    /** 监听新日志(日志页实时刷新用)。 */
    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private inline fun log(tag: String, level: String, message: String, block: () -> Unit) {
        if (enabled) {
            block()
            append(tag, level, message)
        }
    }

    private fun append(tag: String, level: String, message: String) {
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER_LINES) buffer.removeFirst()
            buffer.addLast("${timeFormat.format(Date())} $level/$tag: $message")
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
