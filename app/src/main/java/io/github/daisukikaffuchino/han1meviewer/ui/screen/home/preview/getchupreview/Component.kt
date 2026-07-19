package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreviewDetail
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyRow
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeGetchuPreviewItem

@Composable
internal fun GetchuPreviewItemCard(
    item: GetchuPreview.Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = getchuImageRequest(item.coverUrl),
                imageLoader = imageLoader,
                contentDescription = item.title,
                modifier = Modifier
                    .size(width = 108.dp, height = 148.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.h_chan_loading),
                error = painterResource(R.drawable.h_chan_load_failed)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                item.brand?.let {
                    Text(
                        "${stringResource(R.string.brand)}: $it",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item.price?.let {
                    Text(
                        "$it (JPY)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun GetchuTextSection(section: GetchuPreviewDetail.TextSection) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GetchuSectionTitle(getchuTextSectionTitle(section.title))
        ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SelectionContainer {
                    Text(
                        section.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun getchuTextSectionTitle(title: String): String {
    return when {
        title.contains("商品紹介") -> stringResource(R.string.getchu_product_intro)
        title.contains("ストーリー") -> stringResource(R.string.getchu_story)
        title.contains("スタッフ") -> stringResource(R.string.getchu_staff)
        else -> title
    }
}

@Composable
internal fun GetchuSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
internal fun GetchuRelatedRow(
    title: String,
    items: List<GetchuPreview.Item>,
    onNavigateToDetail: (String) -> Unit,
    imageLoader: ImageLoader,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GetchuSectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                GetchuPreviewItemCard(
                    item = item,
                    onClick = { onNavigateToDetail(item.id) },
                    modifier = Modifier.width(260.dp),
                    imageLoader = imageLoader
                )
            }
        }
    }
}

@Preview
@Composable
private fun GetchuPreviewItemCardPreview() {
    ComponentPreview {
        val context = LocalContext.current
        GetchuPreviewItemCard(
            item = fakeGetchuPreviewItem,
            onClick = {},
            imageLoader = ImageLoader(context).newBuilder().build()
        )
    }
}

@Preview
@Composable
private fun GetchuRelatedRowPreview() {
    ComponentPreview {
        val context = LocalContext.current
        GetchuRelatedRow(
            title = "标题",
            items = listOf(fakeGetchuPreviewItem),
            onNavigateToDetail = {},
            imageLoader = ImageLoader(context).newBuilder().build()
        )
    }
}
