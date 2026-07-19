package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview.GetchuPreviewDetailScreen
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview.GetchuPreviewScreen
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview.GetchuPreviewViewModel

@Composable
fun GetchuPreviewRouteScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
) {
    val viewModel: GetchuPreviewViewModel = viewModel()
    GetchuPreviewScreen(
        onBack = onBack,
        onNavigateToDetail = onNavigateToDetail,
        viewModel = viewModel,
    )
}

@Composable
fun GetchuPreviewDetailRouteScreen(
    route: GetchuPreviewDetailRoute,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToVideoUrl: (String) -> Unit,
) {
    val viewModel: GetchuPreviewViewModel = viewModel()
    GetchuPreviewDetailScreen(
        id = route.id,
        onBack = onBack,
        onNavigateToDetail = onNavigateToDetail,
        onNavigateToVideoUrl = onNavigateToVideoUrl,
        viewModel = viewModel,
    )
}
