package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.creatorcenter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.CreatorSort
import io.github.daisukikaffuchino.han1meviewer.logic.model.CreatorUploadingItem
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview

/**
 * 排序筛选行：最新/热门/最旧。
 */
@Composable
internal fun CreatorSortRow(
    sort: CreatorSort,
    onRefresh: (CreatorSort) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CreatorSortChip(
            text = stringResource(R.string.sort_by_newest),
            selected = sort == CreatorSort.Latest
        ) { onRefresh(CreatorSort.Latest) }
        CreatorSortChip(
            text = stringResource(R.string.popular),
            selected = sort == CreatorSort.Popular
        ) { onRefresh(CreatorSort.Popular) }
        CreatorSortChip(
            text = stringResource(R.string.sort_by_oldest),
            selected = sort == CreatorSort.Oldest
        ) { onRefresh(CreatorSort.Oldest) }
    }
}

@Composable
private fun CreatorSortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick, label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * 审核中视频卡片。
 */
@Composable
internal fun CreatorUploadingCard(
    item: CreatorUploadingItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusInfo = item.reviewStatus.toReviewStatusInfo()
    Card(
        onClick = onClick, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, statusInfo.color.copy(alpha = 0.22f)), modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.h_chan_loading),
                    error = painterResource(R.drawable.h_chan_load_failed),
                    fallback = painterResource(R.drawable.h_chan_load_failed),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(
                            color = statusInfo.color.copy(alpha = 0.16f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusInfo.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusInfo.color,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item.duration?.let {
                    Text(
                        text = it, color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(
                                Color.Black.copy(alpha = 0.65f),
                                RoundedCornerShape(topStart = 8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.currentArtist.orEmpty() + if (!item.uploadTime.isNullOrBlank()) " · ${item.uploadTime}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.remoteVideoUrl.extractFileNameFromUrl()
                            .ifBlank { "Unknown Source" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun CreatorUploadingCardPreview() {
    ComponentPreview {
        CreatorUploadingCard(
            item = CreatorUploadingItem(
                title = "Sample", coverUrl = "https://picsum.photos/400/240", videoCode = "5103",
                duration = "04:54", currentArtist = "_shinobu_", uploadTime = "1min ago",
                remoteVideoUrl = "https://example.com/video.mp4", reviewStatus = "排隊",
            ), onClick = {})
    }
}

@Preview
@Composable
private fun CreatorSortRowPreview() {
    ComponentPreview {
        CreatorSortRow(
            sort = CreatorSort.Latest,
            onRefresh = {}
        )
    }
}