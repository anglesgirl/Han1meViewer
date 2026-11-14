package com.yenaly.han1meviewer.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import java.util.Locale

/**
 * 关键 H 帧倒计时浮层。
 *
 * 迁移自旧版 `HJzvdStd.onProgress` 的 `tvTimer` 逻辑：
 * 播放位置临近某个关键帧（`0 <= keyframe - position < remindAheadMs`）时，
 * 显示 `#序号`、备注（小字，可开关）与剩余秒数（粗体大字），一次只取第一个命中的关键帧。
 *
 * @param positionMs 当前播放位置
 * @param keyframes 关键帧实体
 * @param remindAheadMs 提前提醒的毫秒数（[com.yenaly.han1meviewer.Preferences.whenCountdownRemind]）
 * @param showComment 是否显示序号与备注
 */
@Composable
fun KeyframeCountdownOverlay(
    positionMs: Long,
    keyframes: HKeyframeEntity?,
    remindAheadMs: Int,
    showComment: Boolean,
    modifier: Modifier = Modifier,
) {
    val countdown = remember(positionMs, keyframes, remindAheadMs) {
        keyframes?.keyframes?.forEachIndexed index@{ index, kf ->
            val interval = kf.position - positionMs
            if (interval in 0 until remindAheadMs) {
                val timeText = if (interval / 1_000L >= 1) {
                    (interval / 1_000L + 1).toString()
                } else {
                    String.format(Locale.US, "%.1f", interval / 1_000f)
                }
                return@remember Countdown(
                    kfIndexLabel = "#${index + 1}",
                    prompt = kf.prompt,
                    timeText = timeText,
                )
            }
        }
        null
    }

    AnimatedVisibility(
        visible = countdown != null,
        modifier = modifier,
    ) {
        val current = countdown ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showComment) {
                    val label = buildString {
                        append(current.kfIndexLabel)
                        current.prompt?.takeIf { it.isNotBlank() }?.let { append(" $it") }
                    }
                    if (label.isNotBlank()) {
                        Text(
                            text = label,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = current.timeText,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 倒计时展示数据。 */
private data class Countdown(
    val kfIndexLabel: String,
    val prompt: String?,
    val timeText: String,
)
