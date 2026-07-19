package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.PreviewScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.CommentViewModel
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.PreviewViewModel

@Composable
fun PreviewRouteScreen(
    activity: MainActivity,
    onBack: () -> Unit,
    onNavigateToGetchuPreview: () -> Unit,
    onNavigateToPreviewComment: (String, String) -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val previewViewModel: PreviewViewModel = viewModel()
    val commentViewModel: CommentViewModel = viewModel(viewModelStoreOwner = activity)

    PreviewScreen(
        onBack = onBack,
        onNavigateToGetchuPreview = onNavigateToGetchuPreview,
        onNavigateToPreviewComment = onNavigateToPreviewComment,
        onNavigateToVideo = onNavigateToVideo,
        previewViewModel = previewViewModel,
        commentViewModel = commentViewModel,
    )
}
