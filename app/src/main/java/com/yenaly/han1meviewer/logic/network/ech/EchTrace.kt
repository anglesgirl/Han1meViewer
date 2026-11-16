package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ECH/H3 关键事件的**内存环形缓冲**，供诊断日志导出使用。
 *
 * 为什么不直接靠 logcat：**Android 对每个 App 的日志有速率限流**。
 * 实测荣耀那台（图片全挂）导出 1676 行日志里 **1638 行是 CoilError 的堆栈**，
 * 系统随即打了 `LOGLIMIT` —— 结果 `HY-ECH` 的 `Log.i` **一条都没留下**，
 * 恰好把最需要的诊断信息丢干净了（而网络正常的那台 CoilError 少，日志反而看得见）。
 *
 * 所以关键事件**双写**：既走 [Log]（开发时方便），也进这个环形缓冲，
 * 导出诊断日志时和 logcat 一起落盘 —— 不依赖 logcat 能否幸存。
 *
 * 只放**事件**，不放堆栈（缓冲很小，只留最近 [CAPACITY] 条）。
 */
object EchTrace {

    private const val TAG = "HY-ECH-TRACE"

    private const val CAPACITY = 300

    private val ring = ArrayDeque<String>()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 记录一条关键事件（thread-safe；同时打到 logcat）。 */
    fun event(message: String) {
        val line = "${fmt.format(Date())} $message"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        Log.i(TAG, message)
    }

    /** 当前缓冲内容（按时间顺序），供 [com.yenaly.han1meviewer.util.LogExporter] 落盘。 */
    fun dump(): String = synchronized(ring) {
        if (ring.isEmpty()) "（无：本次会话还没有 ECH/H3 事件）" else ring.joinToString("\n")
    }
}
