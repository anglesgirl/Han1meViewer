package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeTopAppBar
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview

/**
 * 渲染首页顶部栏，包含抽屉入口、搜索入口和新番列表入口。
 *
 * @param onOpenDrawer 点击抽屉按钮时调用。
 * @param onSearchClick 点击搜索框时调用。
 * @param onNavigateToPreview 点击新番按钮时调用。
 * @param modifier 应用于顶部栏根布局的修饰符。
 */
@Composable
fun HomePageTopBar(
    onOpenDrawer: () -> Unit,
    onSearchClick: () -> Unit,
    onNavigateToPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    HanimeTopAppBar(
        modifier = modifier,
        title = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onSearchClick)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.global_search),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    painter = painterResource(R.drawable.menu_24px),
                    contentDescription = stringResource(R.string.open_menu),
                )
            }
        },
        actions = {
            IconButton(onClick = onNavigateToPreview) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_newspaper_24),
                    contentDescription = stringResource(R.string.hanime_list),
                )
            }
        },
    )
}

@Preview(showBackground = true, name = "首页顶栏")
@Composable
private fun HomePageTopBarPreview() {
    ComponentPreview {
        HomePageTopBar(
            onOpenDrawer = {},
            onSearchClick = {},
            onNavigateToPreview = {}
        )
    }
}
