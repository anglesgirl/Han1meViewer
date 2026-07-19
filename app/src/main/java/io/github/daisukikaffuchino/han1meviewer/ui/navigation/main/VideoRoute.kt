package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.screen.video.VideoRouteHostScreen

@Composable
fun VideoRouteScreen(
    activity: MainActivity,
    route: VideoRoute,
) {
    VideoRouteHostScreen(activity = activity, route = route)
}
