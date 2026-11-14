package com.yenaly.han1meviewer.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import com.yenaly.han1meviewer.ui.player.PlayerDefaults
import com.yenaly.han1meviewer.ui.player.PlayerUtils
import kotlin.math.roundToInt

/**
 * 侧边弹窗种类。
 */
enum class PlayerSheetType {
    /** 倍速。 */
    Speed,

    /** 清晰度。 */
    Quality,

    /** Anime4K 超分（仅 MPV 内核）。 */
    SuperResolution,

    /** 关键 H 帧列表。 */
    Keyframes,
}

/**
 * 气泡选项行：紧凑固定高度，选中项高亮 + 勾选标记。
 */
@Composable
private fun BubbleOptionItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.88f)
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 漫画对话泡样式的气泡弹窗（倍速 / 清晰度 / 超分共用）。
 *
 * 从触发按钮的位置弹出，气泡带一枚指向锚点的小三角；高度按
 * [itemCount] 预计算（每行 [ITEM_HEIGHT]，最多同时展示 [maxVisibleItems] 行，
 * 超出部分在气泡内滚动），因此定位一次成型无跳变。
 * 弹出期间点击气泡外任意处关闭（由 [onDismiss] 层承接）。
 *
 * @param anchorX 锚点中心 x（px，相对播放器左上）
 * @param anchorEdgeY 锚点贴合边 y（px；[preferAbove] 为 true 时是锚点顶边，否则底边）
 * @param preferAbove true = 气泡弹在锚点上方（底栏按钮），false = 下方（顶栏按钮）
 * @param playerWidthPx 播放器宽度（px，用于水平钳制）
 * @param itemCount 选项数量（用于预算高度）
 * @param maxVisibleItems 气泡最多同时展示的行数，超出部分滚动查看
 * @param content 气泡内容
 */
@Composable
fun PlayerBubblePopup(
    anchorX: Float,
    anchorEdgeY: Float,
    preferAbove: Boolean,
    playerWidthPx: Int,
    itemCount: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxVisibleItems: Int = 6,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bubbleWidth = 172.dp
    val arrowSize = 10.dp
    val margin = 8.dp
    val verticalPad = 10.dp

    with(density) {
        val widthPx = bubbleWidth.toPx()
        val arrowPx = arrowSize.toPx()
        val marginPx = margin.toPx()
        val visibleRows = itemCount.coerceAtMost(maxVisibleItems)
        val heightPx = visibleRows * ITEM_HEIGHT.toPx() + verticalPad.toPx() * 2

        // 水平：气泡中心对齐锚点，钳制在播放器内
        val leftPx = (anchorX - widthPx / 2)
            .coerceIn(marginPx, (playerWidthPx - widthPx - marginPx).coerceAtLeast(marginPx))
        // 垂直
        val topPx = if (preferAbove) {
            anchorEdgeY - arrowPx - heightPx
        } else {
            anchorEdgeY + arrowPx
        }
        // 三角相对气泡的 x（跟随锚点，钳制在气泡范围内）
        val triangleCenterPx = (anchorX - leftPx)
            .coerceIn(arrowPx + 6f, widthPx - arrowPx - 6f)

        Box(modifier = modifier) {
            Surface(
                modifier = Modifier
                    .offsetPx(IntOffset(leftPx.roundToInt(), topPx.roundToInt()))
                    .width(bubbleWidth),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xF0101014),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.08f),
                ),
            ) {
                Column(modifier = Modifier.padding(vertical = verticalPad)) {
                    // 选项多于可见行数时在气泡内滚动，而不是无限撑高气泡。
                    // content() 产生多个并列行，必须经 Column 纵向排列——
                    // 直接放进 Box 会全部堆叠在左上角。
                    Box(
                        modifier = Modifier
                            .heightIn(max = ITEM_HEIGHT * maxVisibleItems)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column {
                            content()
                        }
                    }
                }
            }
            // 指向锚点的小三角（旋转 45° 的方块裁一半视觉）
            Box(
                modifier = Modifier
                    .offsetPx(
                        IntOffset(
                            (leftPx + triangleCenterPx - arrowPx).roundToInt(),
                            if (preferAbove) {
                                (topPx + heightPx).roundToInt()
                            } else {
                                (topPx - arrowPx).roundToInt()
                            },
                        )
                    )
                    .size(arrowSize)
                    .rotate(45f)
                    .background(Color(0xF0101014)),
            )
        }
    }
}

/** 气泡单行高度。 */
internal val ITEM_HEIGHT = 38.dp

/** [IntOffset] 版 offset（像素定位气泡与三角）。 */
private fun Modifier.offsetPx(position: IntOffset): Modifier =
    this.then(Modifier.offset { position })

/**
 * 气泡关闭承接层：吞掉气泡外区域的点击（不透传给播放器手势层）。
 */
@Composable
fun BubbleDismissScrim(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    )
}

/**
 * 倍速气泡内容。
 */
@Composable
internal fun SpeedBubble(
    currentSpeedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    PlayerDefaults.speedStringArray.forEachIndexed { index, item ->
        BubbleOptionItem(
            text = item,
            selected = index == currentSpeedIndex,
            onClick = { onSelect(index) },
        )
    }
}

/**
 * 清晰度气泡内容。
 */
@Composable
internal fun QualityBubble(
    qualities: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    qualities.forEachIndexed { index, item ->
        BubbleOptionItem(
            text = item,
            selected = index == currentIndex,
            onClick = { onSelect(index) },
        )
    }
}

/**
 * Anime4K 超分气泡内容（仅 MPV 内核显示）。
 */
@Composable
internal fun SuperResolutionBubble(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(
        stringResource(R.string.super_resolution_off),
        stringResource(R.string.super_resolution_performance),
        stringResource(R.string.super_resolution_quality),
    )
    options.forEachIndexed { index, item ->
        BubbleOptionItem(
            text = item,
            selected = index == currentIndex,
            onClick = { onSelect(index) },
        )
    }
}

/**
 * 通用右侧面板容器（关键帧列表专用）。
 *
 * 从右缘滑入、半透明遮罩，点击遮罩关闭。宽度与旧版一致（240dp）。
 *
 * @param visible 是否显示
 * @param onDismiss 关闭回调
 * @param content 面板内容
 */
@Composable
fun PlayerSideSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                Surface(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight(),
                    color = Color.Black.copy(alpha = 0.72f),
                ) {
                    Box(modifier = Modifier.padding(vertical = 48.dp)) {
                        content()
                    }
                }
            }
        }
    }
}


/**
 * 关键 H 帧面板。
 *
 * 点击行 seek 到对应时刻；本地关键帧（[HKeyframeEntity.author] 为 null）可编辑/删除；
 * 空列表显示「长按🥵添加」提示。
 *
 * @param keyframes 关键帧实体（含列表），null 或空列表显示空态
 * @param isLocalKeyframes 是否本地关键帧（共享关键帧只读）
 * @param onSeek 点击行 seek
 * @param onEdit 编辑回调
 * @param onDelete 删除回调
 */
@Composable
internal fun KeyframesSheet(
    keyframes: HKeyframeEntity?,
    isLocalKeyframes: Boolean,
    onSeek: (HKeyframeEntity.Keyframe) -> Unit,
    onEdit: (HKeyframeEntity.Keyframe) -> Unit,
    onDelete: (HKeyframeEntity.Keyframe) -> Unit,
) {
    val list = keyframes?.keyframes.orEmpty()
    if (list.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.here_is_empty),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.long_press_to_add_h_keyframe),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn {
        itemsIndexed(list, key = { _, item -> item.position }) { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(item) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = PlayerUtils.stringForTime(item.position),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "#${index + 1}",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    item.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                        Text(
                            text = "➥ $prompt",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isLocalKeyframes) {
                    IconButton(onClick = { onEdit(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.modify_h_keyframe),
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.sure_to_delete),
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
