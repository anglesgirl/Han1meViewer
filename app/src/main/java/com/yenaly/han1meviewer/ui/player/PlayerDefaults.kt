package com.yenaly.han1meviewer.ui.player

import androidx.annotation.IntRange
import com.yenaly.han1meviewer.ui.player.PlayerDefaults.speedArray

/**
 * 播放器默认参数与常量。
 *
 * 从旧版 JZVD 播放器（`HJzvdStd`）的伴生对象常量迁移而来，
 * 供 [com.yenaly.han1meviewer.Preferences]、播放器内核与设置页共同使用，
 * 避免各处散落魔法数字。
 */
object PlayerDefaults {

    /**
     * 滑动手势判定阈值（px）。
     *
     * 手指位移超过该距离后，才会被识别为「横滑调进度 / 竖滑调亮度音量」的拖动手势。
     */
    const val GESTURE_THRESHOLD = 10

    /**
     * 默认的进度条滑动灵敏度（1..9）。
     *
     * 值越大，滑动相同距离时 seek 的距离越小。
     */
    const val DEF_PROGRESS_SLIDE_SENSITIVITY = 5

    /**
     * 默认关键帧倒计时提醒秒数。
     */
    const val DEF_COUNTDOWN_SEC = 10

    /**
     * 默认播放速度。
     */
    const val DEF_SPEED = 1.0f

    /**
     * 默认播放速度在 [speedArray] 中的下标。
     */
    const val DEF_SPEED_INDEX = 4

    /**
     * 默认长按快进为原速度的倍数。
     */
    const val DEF_LONG_PRESS_SPEED_TIMES = 2.5f

    /**
     * 默认超分（Anime4K）选项下标，0 表示关闭。
     */
    const val DEF_SUPER_RESOLUTION_INDEX = 0

    /**
     * 可选倍速列表，从 0.1x 到 3.0x 共 13 档。
     */
    val speedArray = floatArrayOf(
        0.1f, 0.3f, 0.5f, 0.75f,
        1.0f, 1.25f, 1.5f, 1.75f,
        2.0f, 2.25f, 2.5f, 2.75f,
        3.0f,
    )

    /**
     * [speedArray] 对应的显示文本，例如 `1.0x`。
     */
    val speedStringArray = Array(speedArray.size) { "${speedArray[it]}x" }

    /**
     * 将用户设置的灵敏度（1..9）转换为实际的进度滑动分母。
     *
     * 高灵敏度照旧，低灵敏度差异较小，高灵敏度按倍数放大，
     * 很多用户对滑动精度要求较高，因此 6 以上逐级放大差异。
     */
    fun toRealSensitivity(@IntRange(from = 1, to = 9) sensitivity: Int): Int {
        return when (sensitivity) {
            1, 2, 3, 4, 5 -> sensitivity
            6 -> 7
            7 -> 10
            8 -> 20
            9 -> 40
            else -> DEF_PROGRESS_SLIDE_SENSITIVITY
        }
    }
}
