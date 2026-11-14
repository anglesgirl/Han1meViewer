package com.yenaly.han1meviewer.ui.player

import java.util.Locale

/**
 * 播放器工具函数。
 */
object PlayerUtils {

    /**
     * 将毫秒时间格式化为 `mm:ss` 或 `h:mm:ss` 形式的字符串。
     *
     * 替代旧版 `cn.jzvd.JZUtils.stringForTime`。
     */
    fun stringForTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60 % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
