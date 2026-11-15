package com.yenaly.han1meviewer.ui.screen.video

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ShareCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.yenaly.han1meviewer.util.popBackStackSafe
import com.yenaly.han1meviewer.FirebaseConstants
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.getHanimeVideoLink
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.dao.CheckInRecordDatabase
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.exception.ParseException
import com.yenaly.han1meviewer.logic.model.HanimeVideo
import com.yenaly.han1meviewer.logic.model.SearchOption
import com.yenaly.han1meviewer.logic.state.VideoLoadingState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.ui.activity.MainActivity
import com.yenaly.han1meviewer.ui.bridge.VideoPageHost
import com.yenaly.han1meviewer.ui.component.ConfirmDialog
import com.yenaly.han1meviewer.ui.component.GlobalDialogs
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.han1meviewer.PermissionRequester
import com.yenaly.han1meviewer.ui.navigation.navigateSafely
import com.yenaly.han1meviewer.ui.navigation.main.VideoRoute
import com.yenaly.han1meviewer.ui.component.TextInputField
import com.yenaly.han1meviewer.ui.player.HanimePlayerController
import com.yenaly.han1meviewer.ui.player.PlayerState
import com.yenaly.han1meviewer.ui.player.kernel.KernelType
import com.yenaly.han1meviewer.ui.player.surface.rememberMovablePlayerSurface
import com.yenaly.han1meviewer.ui.player.video.HanimeVideoPlayer
import com.yenaly.han1meviewer.ui.view.video.VideoPlayerAppBarBehavior
import com.yenaly.han1meviewer.ui.viewmodel.CommentViewModel
import com.yenaly.han1meviewer.ui.viewmodel.VideoViewModel
import com.yenaly.han1meviewer.util.checkBadGuy
import com.yenaly.han1meviewer.util.loadAssetAs
import com.yenaly.han1meviewer.util.OrientationManager
import com.yenaly.han1meviewer.util.browse
import com.yenaly.han1meviewer.util.copyToClipboard
import com.yenaly.han1meviewer.util.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun VideoRouteHostScreen(
    activity: MainActivity,
    route: VideoRoute,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val viewModel: VideoViewModel = viewModel()
    val commentViewModel: CommentViewModel = viewModel()
    val player = remember(route.videoCode, route.localUri) {
        HanimePlayerController(activity, KernelType.ExoPlayer)
    }
    val shell = remember(route.videoCode, route.localUri) { VideoRouteShell(activity) }
    var isFullscreen by remember(route.videoCode, route.localUri) { mutableStateOf(false) }
    val fullscreenHandler = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val previousRequestedOrientation = remember(route.videoCode, route.localUri) {
        mutableStateOf(activity.requestedOrientation)
    }
    val previousSystemBarsVisible = remember(route.videoCode, route.localUri) {
        mutableStateOf(true)
    }
    val windowInsetsController = remember(activity) {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    }
    val genres = remember(Preferences.baseUrl) {
        loadAssetAs<List<SearchOption>>(
            if (Preferences.baseUrl == com.yenaly.han1meviewer.HanimeConstants.HANIME_URL[3]) {
                "search_options/genre_av.json"
            } else {
                "search_options/genre.json"
            }
        ).orEmpty()
    }
    val hostUiState by viewModel.videoHostUiStateFlow.collectAsStateWithLifecycle()
    val relatedItems =
        viewModel.hanimeVideoFlow.collectAsStateWithLifecycle().value?.relatedHanimes.orEmpty()
    val stringLongPressShare = remember(activity) {
        activity.getString(R.string.long_press_share_to_copy)
    }

    commentViewModel.code = route.videoCode
    viewModel.fromDownload = route.videoCode == "-1" || route.localUri != null

    var checkedQuality by remember(
        route.videoCode,
        route.localUri
    ) { mutableStateOf<String?>(null) }
    var pendingDownloadPrompt by remember(route.videoCode, route.localUri) {
        mutableStateOf<DownloadPromptState?>(null)
    }
    var videoTitle by remember(route.videoCode, route.localUri) { mutableStateOf<String?>(null) }
    var isSideRelatedCollapsed by remember { mutableStateOf(false) }
    var showAddHKeyframeDialog by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var keyframeEntity by remember(route.videoCode) { mutableStateOf<HKeyframeEntity?>(null) }
    var unsubscribeArtist by remember { mutableStateOf<HanimeVideo.Artist?>(null) }
    var showDownloadPermissionDialog by remember { mutableStateOf(false) }
    val useLargeVideoLayout = currentWindowAdaptiveInfoV2()
        .windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val actions = remember(activity, scope, viewModel, genres) {
        VideoRouteActions(
            context = activity,
            scope = scope,
            viewModel = viewModel,
            genres = genres,
            requestStoragePermission = { onGranted, onDenied, onPermanentlyDenied ->
                (activity as PermissionRequester).requestStoragePermission(
                    onGranted = onGranted,
                    onDenied = onDenied,
                    onPermanentlyDenied = onPermanentlyDenied,
                )
            },
            onPendingDownloadPromptChange = { pendingDownloadPrompt = it },
            getCheckedQuality = { checkedQuality },
            setCheckedQuality = { checkedQuality = it },
            onStoragePermissionDenied = { activity.navController.popBackStackSafe() },
            onRequestUnsubscribeConfirm = { artist -> unsubscribeArtist = artist },
            onRequestDownloadPermissionSettings = { showDownloadPermissionDialog = true },
        )
    }

    val jzBackCallback = remember(activity) {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    fullscreenHandler.value?.invoke(false)
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    fun setPlayerHeight(height: Int) {
        shell.setPlayerHeight(height)
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (isFullscreen == fullscreen) return
        if (fullscreen) {
            previousRequestedOrientation.value = activity.requestedOrientation
            previousSystemBarsVisible.value = ViewCompat
                .getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
            if (!shell.enterFullscreen(activity.window.decorView as ViewGroup)) return
            isFullscreen = true
            activity.requestedOrientation = if (player.isPortraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            shell.exitFullscreen()
            isFullscreen = false
            activity.requestedOrientation = previousRequestedOrientation.value
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (previousSystemBarsVisible.value) {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        jzBackCallback.isEnabled = fullscreen
    }

    SideEffect { fullscreenHandler.value = ::setFullscreen }

    fun updatePipAction() {
        if (!activity.isInPictureInPictureMode) return
        val isPlaying = player.isPlaying
        val icon = if (isPlaying) {
            Icon.createWithResource(activity, R.drawable.ic_pip_pause_24)
        } else {
            Icon.createWithResource(activity, R.drawable.ic_pip_play_arrow_24)
        }
        val title = if (isPlaying) "Pause Video" else "Play Video"
        val intent = PendingIntent.getBroadcast(
            activity,
            0,
            Intent(MainActivity.ACTION_TOGGLE_PLAY).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = RemoteAction(icon, title, activity.getString(R.string.play_pause), intent)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .build()
        activity.setPictureInPictureParams(params)
    }

    fun changeScreenNormal() {
        if (isFullscreen) setFullscreen(false)
    }

    fun changeScreenFullLandscape() {
        if (!isFullscreen && !player.isPortraitVideo) setFullscreen(true)
    }

    val pageHost = remember(activity, player, shell, viewModel) {
        object : VideoPageHost {
            override fun showCommentBadge(count: Int) {
                viewModel.setCommentBadgeCount(count)
            }

            override fun shouldEnterPip(): Boolean {
                return player.isActiveForPip
            }

            override fun enterPipMode() {
                val intent = PendingIntent.getBroadcast(
                    activity,
                    0,
                    Intent(MainActivity.ACTION_TOGGLE_PLAY).setPackage(activity.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val icon =
                    Icon.createWithResource(activity, R.drawable.ic_pip_pause_24)
                val action = RemoteAction(
                    icon,
                    activity.getString(R.string.play_pause),
                    activity.getString(R.string.play_pause),
                    intent,
                )
                val sourceRect = Rect()
                shell.playerHostView.getGlobalVisibleRect(sourceRect)
                val params = PictureInPictureParams.Builder()
                    .setSourceRectHint(sourceRect)
                    .setAspectRatio(Rational(16, 9))
                    .setActions(listOf(action))
                    .build()
                activity.enterPictureInPictureMode(params)
            }

            override fun onPipModeChanged(isInPip: Boolean) {
                viewModel.setPipMode(isInPip)
                if (isInPip) {
                    viewModel.setPlayerHeightDp(MATCH_PARENT)
                } else if (useLargeVideoLayout &&
                    activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                ) {
                    viewModel.setPlayerHeightDp(350.dp)
                } else {
                    viewModel.setPlayerHeightDp(250.dp)
                }
                setPlayerHeight(viewModel.videoHostUiStateFlow.value.playerHeightDp)
                shell.setTabsVisible(!isInPip)
                if (isInPip) {
                    updatePipAction()
                }
            }

            override fun togglePlayPause() {
                player.togglePlayPause()
                updatePipAction()
            }
        }
    }

    DisposableEffect(
        activity,
        shell,
        player,
        pageHost,
        stringLongPressShare,
        route.videoCode,
        route.localUri
    ) {
        activity.registerCurrentVideoHost(pageHost)
        shell.setPlayerHostContent {
            val playerHostUiState by viewModel.videoHostUiStateFlow.collectAsStateWithLifecycle()
            val videoState by viewModel.hanimeVideoStateFlow.collectAsStateWithLifecycle()
            val playerSurface = rememberMovablePlayerSurface(player)
            if (playerHostUiState.isInPipMode) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    playerSurface(Modifier.fillMaxSize())
                }
            } else {
                HanimeVideoPlayer(
                    controller = player,
                    playerSurface = playerSurface,
                    isFullscreen = isFullscreen,
                    keyframeEntity = keyframeEntity,
                    modifier = Modifier.fillMaxSize(),
                    videoInfoLoading = videoState is VideoLoadingState.Loading,
                    onBack = {
                        if (isFullscreen) setFullscreen(false)
                        else activity.onBackPressedDispatcher.onBackPressed()
                    },
                    onGoHome = {
                        if (activity.resources.getBoolean(R.bool.isTablet)) {
                            activity.navController.popBackStackSafe()
                        }
                        activity.startActivity(Intent(activity, MainActivity::class.java))
                    },
                    onToggleFullscreen = { setFullscreen(!isFullscreen) },
                    onNoVideoUrl = {
                        GlobalToasts.show(
                            activity.getString(R.string.fail_to_get_video_link),
                            level = GlobalToasts.ToastLevel.ERROR,
                        )
                        if (!viewModel.fromDownload) {
                            activity.browse(getHanimeVideoLink(route.videoCode))
                        }
                    },
                    onAddHKeyframeAt = { positionMs ->
                        if (!player.isPlaying) {
                            showAddHKeyframeDialog = positionMs to (videoTitle ?: "Untitled")
                        } else {
                            GlobalToasts.show(
                                activity.getString(R.string.pause_then_long_press),
                                level = GlobalToasts.ToastLevel.WARNING,
                            )
                        }
                    },
                    onEditKeyframe = { keyframe ->
                        GlobalDialogs.show(
                            GlobalDialogs.InputRequest(
                                title = activity.getString(R.string.modify_h_keyframe),
                                fields = listOf(
                                    TextInputField(
                                        label = activity.getString(R.string.position_ms),
                                        initialValue = keyframe.position.toString(),
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                    ),
                                    TextInputField(
                                        label = activity.getString(R.string.prompt),
                                        initialValue = keyframe.prompt.orEmpty(),
                                    ),
                                ),
                                confirmText = activity.getString(R.string.confirm),
                                dismissText = activity.getString(R.string.cancel),
                                onConfirm = { values ->
                                    val position = values.getOrElse(0) { keyframe.position.toString() }
                                        .toLongOrNull() ?: keyframe.position
                                    val prompt = values.getOrElse(1) { keyframe.prompt.orEmpty() }
                                    activity.viewModel.modifyHKeyframe(
                                        route.videoCode,
                                        keyframe,
                                        HKeyframeEntity.Keyframe(position = position, prompt = prompt),
                                    )
                                    GlobalToasts.show(
                                        activity.getString(R.string.modify_success),
                                        level = GlobalToasts.ToastLevel.SUCCESS,
                                    )
                                },
                            )
                        )
                    },
                    onDeleteKeyframe = { keyframe ->
                        GlobalDialogs.show(
                            GlobalDialogs.ConfirmRequest(
                                title = activity.getString(R.string.sure_to_delete),
                                message = com.yenaly.han1meviewer.ui.player.PlayerUtils
                                    .stringForTime(keyframe.position),
                                confirmText = activity.getString(R.string.confirm),
                                dismissText = activity.getString(R.string.cancel),
                                onConfirm = {
                                    activity.viewModel.removeHKeyframe(route.videoCode, keyframe)
                                    GlobalToasts.show(
                                        activity.getString(R.string.delete_success),
                                        level = GlobalToasts.ToastLevel.SUCCESS,
                                    )
                                },
                            )
                        )
                    },
                )
            }
        }
        shell.setTabsHostContent {
            val videoState by viewModel.hanimeVideoStateFlow.collectAsStateWithLifecycle()
            VideoRouteContent(
                videoCode = route.videoCode,
                videoState = videoState,
                videoViewModel = viewModel,
                commentViewModel = commentViewModel,
                fromDownload = viewModel.fromDownload,
                pendingDownloadPrompt = pendingDownloadPrompt,
                onPendingDownloadPromptChange = { pendingDownloadPrompt = it },
                onRetry = { viewModel.getHanimeVideo(route.videoCode, route.localUri) },
                onOpenVideo = { item -> activity.showVideoDetailFragment(item.videoCode) },
                onOpenArtist = actions::openArtistSearch,
                onNavigateToSearch = actions::openTagSearch,
                onToggleSubscribe = actions::toggleArtistSubscription,
                onToggleFavorite = actions::toggleFavorite,
                onRateVideo = actions::rateVideo,
                onManageMyList = actions::updateMyListSelection,
                onQuickCheckIn = { record ->
                    val normalizedRecord = if (record.sideDishes.contains("\u001E")) {
                        record
                    } else {
                        record.copy(sideDishes = "${record.sideDishes}\u001E${route.videoCode}")
                    }
                    scope.launch(Dispatchers.IO) {
                        CheckInRecordDatabase.getDatabase(activity).checkInDao()
                            .insert(normalizedRecord)
                        withContext(Dispatchers.Main) {
                            GlobalToasts.show(activity.getString(R.string.checkin), level = GlobalToasts.ToastLevel.SUCCESS)
                        }
                    }
                },
                onPrepareDownload = { quality, video ->
                    checkedQuality = quality
                    video?.let(actions::startDownloadFlow)
                },
                onConfirmDownloadPrompt = { video, autoGroupName ->
                    video?.let {
                        actions.confirmPendingDownload(it, pendingDownloadPrompt, autoGroupName)
                    }
                },
                onRequestOpenOfficialDownloadPage = actions::openOfficialDownloadPage,
                onRequestOpenDownloadPermissionSettings = actions::openDownloadPermissionSettings,
                onOpenWebPage = actions::openVideoWebPage,
                onOpenOriginalComic = actions::openOriginalComic,
                onOpenShare = { content, title ->
                    ShareCompat.IntentBuilder(activity)
                        .setType("text/plain")
                        .setText(content)
                        .setChooserTitle(title)
                        .startChooser()
                },
                onCopyText = {
                    it.copyToClipboard()
                    GlobalToasts.show(activity.getString(R.string.copy_to_clipboard), level = GlobalToasts.ToastLevel.INFO)
                },
                onIntroductionLinkClick = actions::openIntroductionLink,
                stringLongPressShare = stringLongPressShare,
                pageHost = pageHost,
            )

            unsubscribeArtist?.let { artist ->
                ConfirmDialog(
                    visible = true,
                    title = activity.getString(R.string.unsubscribe_artist),
                    message = activity.getString(R.string.sure_to_unsubscribe),
                    confirmText = activity.getString(R.string.sure),
                    dismissText = activity.getString(R.string.no),
                    onConfirm = {
                        unsubscribeArtist = null
                        actions.confirmUnsubscribe(artist)
                    },
                    onDismiss = { unsubscribeArtist = null },
                )
            }

            if (showDownloadPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showDownloadPermissionDialog = false },
                    title = { Text(activity.getString(R.string.permission_permanently_denied_title)) },
                    text = { Text(activity.getString(R.string.storage_permission_settings_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDownloadPermissionDialog = false
                            actions.goToDownloadPermissionSettings()
                        }) {
                            Text(activity.getString(R.string.go_to_settings))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDownloadPermissionDialog = false
                            activity.navController.popBackStackSafe()
                        }) {
                            Text(activity.getString(R.string.cancel))
                        }
                    },
                )
            }
        }
        onDispose {
            activity.registerCurrentVideoHost(null)
            if (isFullscreen) setFullscreen(false)
            shell.clear()
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, activity, player, shell, route.videoCode) {
        val orientationManager = OrientationManager(activity) { orientation ->
            if (!useLargeVideoLayout && player.isActiveForPip && !player.isPortraitVideo) {
                if (orientation.isLandscape && !isFullscreen) {
                    changeScreenFullLandscape()
                } else if (orientation == OrientationManager.ScreenOrientation.PORTRAIT && isFullscreen) {
                    changeScreenNormal()
                }
            }
        }
        fun markWatchedIfReachedThreshold() {
            if (viewModel.fromDownload) return
            val duration = player.durationMs
            val position = player.currentPositionMs
            if (duration > 0 && position * 100 >= duration * Preferences.watchedProgressThreshold) {
                scope.launch { DatabaseRepo.WatchHistory.markWatched(route.videoCode) }
            }
        }

        fun autoPlayNextIfEnabled() {
            if (viewModel.fromDownload || !Preferences.autoPlayNext) return
            val videos = viewModel.hanimeVideoFlow.value?.playlist?.video.orEmpty()
            if (videos.isEmpty()) return
            val currentIndex = videos.indexOfFirst { it.videoCode == route.videoCode }
            if (currentIndex < 0) return
            val next = videos.getOrNull(currentIndex + 1) ?: return
            if (isFullscreen) changeScreenNormal()
            val navController = activity.navController
            val currentId = navController.currentBackStackEntry?.destination?.id
            navController.navigateSafely(VideoRoute(next.videoCode, autoPlay = true)) {
                if (currentId != null) popUpTo(currentId) { inclusive = true }
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val progress = player.currentPositionMs
                    scope.launch { DatabaseRepo.WatchHistory.updateProgress(route.videoCode, progress) }
                    markWatchedIfReachedThreshold()
                }

                Lifecycle.Event.ON_STOP -> {
                    if (!activity.isInPictureInPictureMode) {
                        changeScreenNormal()
                        player.pause()
                    }
                }
                else -> Unit
            }
        }
        val playbackStateJob = scope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                player.uiState.collect { state ->
                    when (state.state) {
                        PlayerState.PREPARING, PlayerState.BUFFERING, PlayerState.PLAYING -> {
                            viewModel.setScrollDisabled(true)
                            shell.withBehavior { it.disableScroll = true }
                            shell.setExpanded(expanded = true, animate = true)
                        }

                        PlayerState.PAUSED, PlayerState.COMPLETED, PlayerState.ERROR, PlayerState.IDLE -> {
                            viewModel.setScrollDisabled(false)
                            shell.withBehavior { it.disableScroll = false }
                        }
                    }
                    if (state.state == PlayerState.COMPLETED) {
                        markWatchedIfReachedThreshold()
                        autoPlayNextIfEnabled()
                    }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(orientationManager)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        activity.onBackPressedDispatcher.addCallback(lifecycleOwner, jzBackCallback)
        shell.setOnOffsetChanged { totalScrollRange, verticalOffset ->
            viewModel.setAppBarBottomInsetPx(totalScrollRange + verticalOffset)
            viewModel.setAppBarExpanded(route.videoCode, verticalOffset == 0)
        }
        shell.setExpanded(expanded = viewModel.isAppBarExpanded(route.videoCode), animate = false)
        val initialHeight = if (useLargeVideoLayout) 350.dp else 250.dp
        viewModel.setPlayerHeightDp(initialHeight)
        setPlayerHeight(initialHeight)

        onDispose {
            playbackStateJob.cancel()
            viewModel.setScrollDisabled(false)
            shell.withBehavior { it.disableScroll = false }
            lifecycleOwner.lifecycle.removeObserver(orientationManager)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(
        hostUiState.isInPipMode,
        isSideRelatedCollapsed,
    ) {
        if (hostUiState.isInPipMode) return@LaunchedEffect
        val height = if (useLargeVideoLayout) {
            if (isSideRelatedCollapsed) 500.dp else 400.dp
        } else {
            250.dp
        }
        if (hostUiState.playerHeightDp != height) {
            viewModel.setPlayerHeightDp(height)
            setPlayerHeight(height)
        }
    }

    LaunchedEffect(route.videoCode, route.localUri) {
        checkedQuality = null
        pendingDownloadPrompt = null
        videoTitle = null
        checkBadGuy(activity, R.raw.akarin)
        viewModel.videoCode = route.videoCode
        viewModel.getHanimeVideo(route.videoCode, route.localUri)
    }

    LaunchedEffect(route.videoCode, route.localUri, player, viewModel.fromDownload) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            viewModel.hanimeVideoStateFlow.collect { state ->
                when (state) {
                    is VideoLoadingState.Error -> {
                        state.throwable.localizedMessage?.let { GlobalToasts.show(it, level = GlobalToasts.ToastLevel.ERROR) }
                        if (state.throwable is ParseException) {
                            activity.browse(getHanimeVideoLink(route.videoCode))
                        }
                    }

                    is VideoLoadingState.Loading -> Unit

                    is VideoLoadingState.Success -> {
                        videoTitle = state.info.title
                        if (!viewModel.fromDownload) {
                            viewModel.insertWatchHistoryWithCover(
                                WatchHistoryEntity(
                                    state.info.coverUrl,
                                    state.info.title,
                                    state.info.uploadTimeMillis,
                                    kotlin.time.Clock.System.now().toEpochMilliseconds(),
                                    route.videoCode,
                                )
                            )
                        }
                        val history = DatabaseRepo.WatchHistory.findBy(route.videoCode)
                        player.setMedia(
                            title = state.info.title,
                            videoUrls = state.info.videoUrls,
                            savedProgressMs = history?.progress ?: 0L,
                            posterUrl = state.info.coverUrl,
                        )
                        if (route.autoPlay) {
                            player.startVideo()
                        }
                    }

                    is VideoLoadingState.NoContent -> {
                        GlobalToasts.show(activity.getString(R.string.video_might_not_exist), level = GlobalToasts.ToastLevel.ERROR)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    LaunchedEffect(route.videoCode, player) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            viewModel.observeKeyframe(route.videoCode).collect {
                keyframeEntity = it
                viewModel.hKeyframes = it
            }
        }
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            viewModel.modifyHKeyframeFlow.collect { (success, reason) ->
                val level = if (success) {
                    GlobalToasts.ToastLevel.SUCCESS
                } else {
                    GlobalToasts.ToastLevel.ERROR
                }
                GlobalToasts.show(reason, level = level)
            }
        }
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            viewModel.modifyMyListFlow.collect { state ->
                when (state) {
                    is WebsiteState.Error -> GlobalToasts.show(
                        activity.getString(R.string.add_failed),
                        level = GlobalToasts.ToastLevel.ERROR,
                    )

                    is WebsiteState.Success -> GlobalToasts.show(
                        activity.getString(R.string.add_success),
                        level = GlobalToasts.ToastLevel.SUCCESS,
                    )

                    WebsiteState.Loading -> Unit
                }
            }
        }
    }

    LaunchedEffect(viewModel, route.videoCode) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            viewModel.loadDownloadedFlow.collect { entity ->
                val newQuality = checkedQuality ?: return@collect
                pendingDownloadPrompt = DownloadPromptState(
                    newQuality = newQuality,
                    oldQuality = entity?.quality,
                )
            }
        }
    }

    VideoShellContent(
        isExpandedLayout = useLargeVideoLayout,
        isInPipMode = hostUiState.isInPipMode,
        relatedItems = relatedItems,
        relatedAsTab = Preferences.relatedVideoAsTab,
        onHideRelatedInIntroChange = { viewModel.hideRelatedInIntro = it },
        onSidePaneCollapsedChange = { isSideRelatedCollapsed = it },
        onTabsBesidePlayerChange = { beside ->
            if (beside) viewModel.setAppBarBottomInsetPx(0)
        },
        onOpenVideo = { item -> activity.showVideoDetailFragment(item.videoCode) },
        mainHostFactory = {
            shell.restoreCollapsedStructure()
            shell.mainHostView.also { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
        },
        playerHostFactory = { shell.takePlayerHost() },
        tabsHostFactory = { shell.takeTabsHost() },
        modifier = Modifier.fillMaxSize(),
    )

    showAddHKeyframeDialog?.let { (currentPosition, title) ->
        ConfirmDialog(
            visible = true,
            title = activity.getString(R.string.add_to_h_keyframe),
            message = buildString {
                appendLine(activity.getString(R.string.sure_to_add_to_h_keyframe))
                append(activity.getString(R.string.current_position_d_ms, currentPosition))
            },
            confirmText = activity.getString(R.string.confirm),
            dismissText = activity.getString(R.string.cancel),
            onConfirm = {
                viewModel.appendHKeyframe(
                    route.videoCode,
                    title,
                    HKeyframeEntity.Keyframe(position = currentPosition, prompt = null),
                )
                Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT) {
                    param(FirebaseAnalytics.Param.ITEM_ID, FirebaseConstants.H_KEYFRAMES)
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, FirebaseConstants.H_KEYFRAMES)
                }
                showAddHKeyframeDialog = null
            },
            onDismiss = { showAddHKeyframeDialog = null },
        )
    }
}

private class VideoRouteShell(private val activity: MainActivity) {
    private val context: Context = activity
    private val rootView = CoordinatorLayout(context).apply {
        fitsSystemWindows = true
        layoutParams = ViewGroup.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
        )
    }

    private val appBarLayout = AppBarLayout(context).apply {
        layoutParams = CoordinatorLayout.LayoutParams(
            MATCH_PARENT,
            WRAP_CONTENT,
        ).apply {
            behavior = VideoPlayerAppBarBehavior(context, null)
        }
    }

    private val collapsingToolbarLayout = CollapsingToolbarLayout(context).apply {
        layoutParams = AppBarLayout.LayoutParams(
            MATCH_PARENT,
            WRAP_CONTENT,
        ).apply {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
        }
    }

    /** [videoPlayerHost] 在折叠工具栏中的原始布局参数，拆分后复原用。 */
    private val videoPlayerHostParams = CollapsingToolbarLayout.LayoutParams(
        MATCH_PARENT,
        WRAP_CONTENT,
    ).apply {
        collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PARALLAX
        parallaxMultiplier = 1f
    }

    /** [videoTabsHost] 在 CoordinatorLayout 中的原始布局参数（含滚动避让行为），拆分后复原用。 */
    private val videoTabsHostParams = CoordinatorLayout.LayoutParams(
        MATCH_PARENT,
        MATCH_PARENT,
    ).apply {
        behavior = AppBarLayout.ScrollingViewBehavior()
    }

    private val videoPlayerHost = FrameLayout(context).apply {
        layoutParams = videoPlayerHostParams
    }

    private val playerComposeView = ComposeView(context).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, 250.dp)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    private val videoTabsHost = ComposeView(context).apply {
        layoutParams = videoTabsHostParams
    }

    private var requestedPlayerHeight = 250.dp
    private var fullscreenParent: ViewGroup? = null
    private var fullscreenParentIndex = -1
    private var fullscreenLayoutParams: ViewGroup.LayoutParams? = null
    private var isFullscreen = false

    /**
     * 平板横屏分栏布局中 tabs 是否已被拆出 CoordinatorLayout。
     *
     * 拆出后 tabs 不再位于 AppBar 下方，避让 AppBar 的滚动内边距应固定为 0。
     */
    private var tabsDetachedFromCoordinator = false

    init {
        videoPlayerHost.addView(playerComposeView)
        collapsingToolbarLayout.addView(videoPlayerHost)
        appBarLayout.addView(collapsingToolbarLayout)
        rootView.addView(videoTabsHost)
        rootView.addView(appBarLayout)
    }

    val mainHostView: View
        get() = rootView

    val playerHostView: FrameLayout
        get() = videoPlayerHost

    fun setPlayerHostContent(content: @Composable () -> Unit) {
        playerComposeView.setContent(content)
    }

    fun enterFullscreen(decorRoot: ViewGroup): Boolean {
        if (isFullscreen) return true
        val parent = videoPlayerHost.parent as? ViewGroup ?: return false
        fullscreenParent = parent
        fullscreenParentIndex = parent.indexOfChild(videoPlayerHost)
        fullscreenLayoutParams = videoPlayerHost.layoutParams
        isFullscreen = true
        // 2026-11-16 Android 16 实测：DecorView 的 onMeasure 遍历 children 途中
        // 若 removeView/addView 改了数组，会读到 null 子 View 直接 NPE 闪退
        // （堆栈只有 framework 帧、无 App 帧）。把搬运推迟到当前 layout
        // 遍历结束后再执行；isFullscreen 立即翻转，重复进出时 posted 任务
        // 靠它鉴别是否过期。
        decorRoot.post {
            if (!isFullscreen) return@post
            val currentParent = videoPlayerHost.parent as? ViewGroup ?: return@post
            if (currentParent === decorRoot) return@post
            currentParent.removeView(videoPlayerHost)
            playerComposeView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            videoPlayerHost.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            decorRoot.addView(videoPlayerHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return true
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        val parent = fullscreenParent
        val params = fullscreenLayoutParams ?: videoPlayerHostParams
        val index = fullscreenParentIndex
        val hostParent = videoPlayerHost.parent as? ViewGroup ?: return
        // 同上：搬运推迟到 layout 遍历结束后；先把待恢复的 parent/params/index
        // 快照到局部变量，避免 posted 任务执行前又被一次 enterFullscreen 覆盖。
        hostParent.post {
            if (isFullscreen) return@post
            (videoPlayerHost.parent as? ViewGroup)?.removeView(videoPlayerHost)
            if (parent != null) {
                val safeIndex = index.coerceIn(0, parent.childCount)
                parent.addView(videoPlayerHost, safeIndex, params)
            } else {
                videoPlayerHost.layoutParams = videoPlayerHostParams
                collapsingToolbarLayout.addView(videoPlayerHost)
            }
            playerComposeView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, requestedPlayerHeight)
            videoPlayerHost.requestLayout()
            fullscreenParent = null
            fullscreenLayoutParams = null
            fullscreenParentIndex = -1
        }
    }

    /** 把 tabs 宿主拆出 CoordinatorLayout，交给 Compose 分栏布局挂载到右栏。 */
    fun takeTabsHost(): ComposeView {
        tabsDetachedFromCoordinator = true
        (videoTabsHost.parent as? ViewGroup)?.removeView(videoTabsHost)
        videoTabsHost.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        return videoTabsHost
    }

    /** 把播放器宿主拆出折叠工具栏，交给 Compose 分栏布局挂载到左栏。 */
    fun takePlayerHost(): FrameLayout {
        (videoPlayerHost.parent as? ViewGroup)?.removeView(videoPlayerHost)
        videoPlayerHost.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return videoPlayerHost
    }

    /** 恢复手机布局结构：播放器宿主回到折叠工具栏，tabs 回到 CoordinatorLayout。 */
    fun restoreCollapsedStructure() {
        tabsDetachedFromCoordinator = false
        if (videoPlayerHost.parent !== collapsingToolbarLayout) {
            (videoPlayerHost.parent as? ViewGroup)?.removeView(videoPlayerHost)
            videoPlayerHost.layoutParams = videoPlayerHostParams
            collapsingToolbarLayout.addView(videoPlayerHost)
        }
        if (videoTabsHost.parent !== rootView) {
            (videoTabsHost.parent as? ViewGroup)?.removeView(videoTabsHost)
            videoTabsHost.layoutParams = videoTabsHostParams
            rootView.addView(videoTabsHost, 0)
        }
    }

    fun setTabsHostContent(content: @Composable () -> Unit) {
        videoTabsHost.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        videoTabsHost.setContent(content)
    }

    fun setTabsVisible(visible: Boolean) {
        videoTabsHost.isVisible = visible
    }

    fun setExpanded(expanded: Boolean, animate: Boolean) {
        appBarLayout.post {
            appBarLayout.setExpanded(expanded, animate)
        }
    }

    fun setOnOffsetChanged(onChanged: (totalScrollRange: Int, verticalOffset: Int) -> Unit) {
        appBarLayout.addOnOffsetChangedListener { appBar, verticalOffset ->
            if (tabsDetachedFromCoordinator) {
                // tabs 已拆至右栏，不再需要避让 AppBar 的滚动内边距
                onChanged(0, 0)
            } else {
                onChanged(appBar.totalScrollRange, verticalOffset)
            }
        }
    }

    fun withBehavior(block: (VideoPlayerAppBarBehavior) -> Unit) {
        val behavior = (appBarLayout.layoutParams as CoordinatorLayout.LayoutParams)
            .behavior as? VideoPlayerAppBarBehavior ?: return
        block(behavior)
    }

    fun setPlayerHeight(height: Int) {
        requestedPlayerHeight = height
        if (isFullscreen) return
        val lp = playerComposeView.layoutParams
        lp.height = height
        playerComposeView.layoutParams = lp
        playerComposeView.requestLayout()
    }

    fun clear() {
        exitFullscreen()
        videoTabsHost.disposeComposition()
        playerComposeView.disposeComposition()
    }
}
