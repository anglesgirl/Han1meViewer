package io.github.daisukikaffuchino.han1meviewer.util

import android.os.SystemClock
import android.view.Choreographer
import io.github.daisukikaffuchino.utils.LogUtil

/**
 * 帧率监视器:通过 Choreographer 统计每帧间隔,
 * 超过 2 帧(≈33ms)记为一次掉帧,每 5 秒窗口写一条聚合日志。
 * 用于诊断"滑动丢帧/一跳一跳"类 UI 卡顿——导出的日志里能看到
 * `JANK frames: N janks in last 5s (max Xms)` 记录。
 *
 * 回调开销极小(每帧一次时间戳比较),可常驻。
 */
object JankMonitor {

    private const val TAG = "Jank"
    private const val FRAME_MS = 16.67
    private const val WINDOW_MS = 5000L

    private var started = false
    private var lastFrame = 0L
    private var windowStart = 0L
    private var jankCount = 0
    private var maxFrameMs = 0.0

    fun start() {
        if (started) return
        started = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.elapsedRealtime()
            if (lastFrame > 0) {
                val dt = now - lastFrame
                if (dt > FRAME_MS * 2) { // 间隔超过 2 帧才算明显掉帧,忽略 1 帧抖动
                    jankCount++
                    if (dt > maxFrameMs) maxFrameMs = dt.toDouble()
                }
                if (now - windowStart >= WINDOW_MS) {
                    if (jankCount > 0) {
                        LogUtil.record(
                            "W", TAG,
                            "frames: $jankCount jank(s) in last 5s (max %.0fms)".format(maxFrameMs)
                        )
                    }
                    jankCount = 0
                    maxFrameMs = 0.0
                    windowStart = now
                }
            } else {
                windowStart = now
            }
            lastFrame = now
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
