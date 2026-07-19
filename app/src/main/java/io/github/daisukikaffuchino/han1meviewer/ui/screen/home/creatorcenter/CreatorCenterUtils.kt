package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.creatorcenter

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState

/**
 * 判断网格是否需要加载更多。
 *
 * 当最后可见项距离末尾不足 4 项时触发，排除 Loading/NoMoreData/Error 状态。
 *
 * @param state 当前页面加载状态
 * @return 是否需要加载更多
 */
fun LazyGridState.canLoadMore(state: PageLoadingState<*>): Boolean {
    if (state is PageLoadingState.Loading || state is PageLoadingState.NoMoreData || state is PageLoadingState.Error) return false
    val total = layoutInfo.totalItemsCount
    if (total == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= total - 4
}

/**
 * 根据服务器返回的审核状态转换为本地化信息和颜色。
 *
 * 服务器返回繁体中文状态值（"已上傳"/"排隊"/"待處理"/"轉檔"），
 * 通过包含匹配映射到对应的文本资源和颜色。
 *
 * @receiver 服务器原始审核状态文本
 * @return 包含本地化文本和颜色的状态信息
 */
@Composable
fun String.toReviewStatusInfo(): ReviewStatusInfo = when {
    contains("已上傳") -> ReviewStatusInfo(
        text = stringResource(R.string.creator_status_uploaded),
        color = Color(0xFF27C93F)
    )
    contains("排隊") -> ReviewStatusInfo(
        text = stringResource(R.string.creator_status_queued),
        color = Color(0xFFF9A825)
    )
    contains("待處理") -> ReviewStatusInfo(
        text = stringResource(R.string.creator_status_pending),
        color = Color(0xFFFF9800)
    )
    contains("轉檔") -> ReviewStatusInfo(
        text = stringResource(R.string.creator_status_transcoding),
        color = Color(0xFF42A5F5)
    )
    else -> ReviewStatusInfo(
        text = this,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 从 URL 中提取文件名。
 *
 * 提取规则：取最后一个 '/' 之后、第一个 '?' 之前的部分。
 *
 * @sample "https://example.com/path/file.mp4?token=123" -> "file.mp4"
 * @sample "https://example.com/path/file.mp4" -> "file.mp4"
 * @sample "file.mp4" -> "file.mp4"
 *
 * @receiver 文件URL
 * @return 提取的文件名，若 URL 为空则返回空字符串
 */
fun String.extractFileNameFromUrl(): String =
    substringAfterLast('/').substringBefore('?')

/**
 * 审核状态信息（本地化文本 + 颜色）
 */
data class ReviewStatusInfo(
    val text: String,
    val color: Color,
)