package com.yenaly.han1meviewer.ui.player.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Battery0Bar
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.Battery3Bar
import androidx.compose.material.icons.outlined.Battery4Bar
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 玻璃拟态背景容器（顶栏/底栏共享视觉）。
 */
@Composable
internal fun FrostedGlassBar(
    modifier: Modifier = Modifier,
    rounded: Int = 20,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(rounded.dp))
            .background(Color.Black.copy(alpha = 0.30f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(rounded.dp)),
    ) {
        content()
    }
}

/**
 * 顶栏小胶囊按钮（🥵关键帧 / 倍速 / Anime4K）。
 *
 * @param onClick 点击回调，携带按钮的 [androidx.compose.ui.layout.LayoutCoordinates]
 * （供气泡弹窗定位锚点）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    var chipCoords by remember {
        mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                }
            )
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .onGloballyPositioned { chipCoords = it }
            .combinedClickable(onClick = { chipCoords?.let(onClick) }, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 电量 + 系统时间指示（全屏顶栏用）。
 *
 * 电量经 [Context.ACTION_BATTERY_CHANGED] 广播刷新，时间每 30 秒刷新。
 */
@Composable
internal fun BatteryTimeChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var batteryPct by remember { mutableIntStateOf(-1) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = level * 100 / scale
                    }
                }
            }
        }
        // 初始读取一次
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    var timeText by remember { mutableStateOf(formatCurrentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = formatCurrentTime()
            delay(30_000.milliseconds)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = batteryIcon(batteryPct),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = timeText,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatCurrentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun batteryIcon(pct: Int): ImageVector = when {
    pct >= 90 -> Icons.Outlined.BatteryFull
    pct >= 70 -> Icons.Outlined.Battery5Bar
    pct >= 50 -> Icons.Outlined.Battery4Bar
    pct >= 30 -> Icons.Outlined.Battery3Bar
    pct >= 15 -> Icons.Outlined.Battery2Bar
    else -> Icons.Outlined.Battery0Bar
}

/**
 * 播放器顶栏。
 *
 * 全屏：返回 + 标题 +（🥵 / 倍速 / Anime4K）胶囊 + 电量时间；
 * 普通窗口：返回 + 回到首页（标题不展示，对齐旧版行为）。
 *
 * 倍速/超分点击回调携带按钮锚点坐标，供气泡弹窗定位。
 */
@Composable
internal fun PlayerTopBar(
    isFullscreen: Boolean,
    title: String,
    showHKeyframeButton: Boolean,
    showSuperResolution: Boolean,
    speedLabel: String,
    superResLabel: String,
    onBack: () -> Unit,
    onGoHome: () -> Unit,
    onSpeedClick: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onKeyframeClick: () -> Unit,
    onKeyframeLongClick: () -> Unit,
    onSuperResolutionClick: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isFullscreen) {
        FrostedGlassBar(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(10.dp))
                if (showHKeyframeButton) {
                    CompactChip(
                        text = "🥵",
                        onClick = { onKeyframeClick() },
                        onLongClick = onKeyframeLongClick,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                CompactChip(text = speedLabel, onClick = onSpeedClick)
                Spacer(modifier = Modifier.width(6.dp))
                if (showSuperResolution) {
                    CompactChip(text = superResLabel, onClick = onSuperResolutionClick)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                BatteryTimeChip()
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onGoHome, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 播放器底栏。
 *
 * 时间（当前/总时长）+ 进度条（含缓冲）+ 循环 + 清晰度 + 全屏切换。
 * 清晰度点击回调携带按钮锚点坐标，供气泡弹窗定位。
 */
@Composable
internal fun PlayerBottomBar(
    currentTime: String,
    totalTime: String,
    progress: Float,
    buffered: Float,
    isLooping: Boolean,
    qualityLabel: String,
    isFullscreen: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    onToggleLooping: () -> Unit,
    onQualityClick: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostedGlassBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        rounded = 18,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            PlayerSlider(
                value = progress,
                buffered = buffered,
                onValueChange = onProgressChange,
                onValueChangeFinished = onProgressChangeFinished,
                modifier = Modifier.height(16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$currentTime / $totalTime",
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onToggleLooping,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Repeat,
                        contentDescription = stringResource(R.string.loop_play),
                        tint = if (isLooping) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                val qualityCoords = remember {
                    mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
                }
                Text(
                    text = qualityLabel,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .onGloballyPositioned { qualityCoords.value = it }
                        .clickable { qualityCoords.value?.let(onQualityClick) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (isFullscreen) {
                            Icons.Outlined.FullscreenExit
                        } else {
                            Icons.Outlined.Fullscreen
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
