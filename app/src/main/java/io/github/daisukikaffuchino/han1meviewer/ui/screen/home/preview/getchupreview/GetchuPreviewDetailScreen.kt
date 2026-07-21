package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.dataOrNull
import io.github.daisukikaffuchino.han1meviewer.pienization
import io.github.daisukikaffuchino.han1meviewer.ui.component.PageContent
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeTopAppBar
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageEmpty
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageError
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageLoading
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.PreviewImageViewerDialog
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.PreviewImageViewerState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberRandomLoadingHint

@Composable
fun GetchuPreviewDetailScreen(
    id: String,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToVideoUrl: (String) -> Unit,
    viewModel: GetchuPreviewViewModel,
) {
    val detailState = remember(id) { viewModel.detailState(id) }
    val state = detailState.collectAsStateWithLifecycle().value
    var imageViewerState by remember { mutableStateOf<PreviewImageViewerState?>(null) }
    val loadingHint = rememberRandomLoadingHint()
    val context = LocalContext.current
    val imageLoader = remember {
        createGetchuImageLoader(context)
    }
    LaunchedEffect(id) { viewModel.getDetail(id) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HanimeTopAppBar(
            title = stringResource(R.string.getchu_preview_detail),
            onBack = onBack,
        )
        PageContent(
            isLoading = state.isFirstPageLoading,
            isError = state.isFirstPageError,
            isEmpty = state.isFirstPageEmpty,
            errorMessage = (state as? PageState.Error)?.throwable?.pienization.toString(),
            onRetry = { viewModel.getDetail(id) },
            modifier = Modifier.fillMaxSize(),
            loadingMessage = loadingHint
        ) {
            state.dataOrNull?.let { detail ->
                GetchuPreviewDetailContent(
                    detail = detail,
                    onOpenImage = { index, images ->
                        imageViewerState = PreviewImageViewerState(images, index)
                    },
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToVideoUrl = onNavigateToVideoUrl,
                    imageLoader = imageLoader
                )
            }
        }
    }

    imageViewerState?.let { viewerState ->
        PreviewImageViewerDialog(
            imageUrls = viewerState.imageUrls,
            initialPage = viewerState.initialPage,
            onDismiss = { imageViewerState = null },
            imageLoader = imageLoader,
        )
    }
}
