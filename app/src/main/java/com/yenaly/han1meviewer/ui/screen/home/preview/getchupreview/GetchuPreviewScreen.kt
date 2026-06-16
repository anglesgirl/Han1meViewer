package com.yenaly.han1meviewer.ui.screen.home.preview.getchupreview

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.state.PageState
import com.yenaly.han1meviewer.logic.state.dataOrNull
import com.yenaly.han1meviewer.ui.component.PageContent
import com.yenaly.han1meviewer.ui.component.isFirstPageEmpty
import com.yenaly.han1meviewer.ui.component.isFirstPageError
import com.yenaly.han1meviewer.ui.component.isFirstPageLoading
import com.yenaly.han1meviewer.ui.preview.ComponentPreview
import com.yenaly.han1meviewer.ui.screen.rememberRandomLoadingHint
import com.yenaly.han1meviewer.util.toNetworkErrorMessageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetchuPreviewScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: GetchuPreviewViewModel,
) {
    var dateCode by rememberSaveable { mutableStateOf(currentGetchuDateCode()) }
    val state = viewModel.previewFlow.collectAsStateWithLifecycle().value
    val dateLabel = remember(dateCode) { getchuDateLabel(dateCode) }
    val loadingHint = rememberRandomLoadingHint()
    val context = LocalContext.current
    val imageLoader = remember {
        createGetchuImageLoader(context)
    }
    LaunchedEffect(dateCode) { viewModel.getPreview(dateCode) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.getchu_preview_title, dateLabel)) },
            navigationIcon = {
                FilledIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = {
                FilledIconButton(onClick = { dateCode = shiftGetchuMonthCode(dateCode, -1) }) {
                    Icon(Icons.AutoMirrored.Default.KeyboardArrowLeft, null)
                }
                FilledIconButton(onClick = { dateCode = shiftGetchuMonthCode(dateCode, 1) }) {
                    Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, null)
                }
            },
        )

        PageContent(
            isLoading = state.isFirstPageLoading,
            isError = state.isFirstPageError,
            isEmpty = state.isFirstPageEmpty || state.dataOrNull?.groups?.isEmpty() == true,
            errorMessage = (state as? PageState.Error)?.throwable?.toNetworkErrorMessageRes()?.let {
                stringResource(it)
            } ?: "",
            onRetry = { viewModel.getPreview(dateCode) },
            modifier = Modifier.fillMaxSize(),
            loadingMessage = loadingHint
        ) {
            state.dataOrNull?.let { preview ->
                GetchuPreviewContent(
                    preview = preview,
                    onOpenDetail = onNavigateToDetail,
                    imageLoader = imageLoader
                )
            }
        }
    }
}

@SuppressLint("ViewModelConstructorInComposable")
@Preview
@Composable
private fun GetchuPreviewScreenPreview() {
    ComponentPreview {
        GetchuPreviewScreen(
            onBack = {},
            onNavigateToDetail = { _ -> },
            viewModel = GetchuPreviewViewModel()
        )
    }
}
