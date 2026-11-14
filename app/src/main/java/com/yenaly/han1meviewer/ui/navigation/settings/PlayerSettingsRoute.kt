package com.yenaly.han1meviewer.ui.navigation.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.player.PlayerDefaults
import com.yenaly.han1meviewer.ui.screen.settings.PlayerSettingsScreen
import com.yenaly.han1meviewer.ui.screen.settings.PlayerSettingsUiState

private const val PLAYER_SHOW_BOTTOM_PROGRESS = "show_bottom_progress"
private const val PLAYER_USE_CRONET = "use_cronet"
private const val PLAYER_SPEED = "player_speed"
private const val PLAYER_SLIDE_SENSITIVITY = "slide_sensitivity"
private const val PLAYER_LONG_PRESS_SPEED_TIMES = "long_press_speed_times"

@Composable
fun PlayerSettingsRouteScreen() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val uiState = remember(refreshKey, context) { buildPlayerSettingsUiState(context) }

    PlayerSettingsScreen(
        state = uiState,
        speedOptions = PlayerDefaults.speedStringArray.zip(
            PlayerDefaults.speedArray.map { it.toString() },
        ),
        longPressSpeedOptions = listOf(
            stringResource(R.string.d_speed_times, 1f) to "1",
            stringResource(R.string.d_speed_times, 1.5f) to "1.5",
            stringResource(R.string.d_speed_times, 2f) to "2",
            "${stringResource(R.string.d_speed_times, 2.5f)} (${stringResource(R.string.default_)})" to "2.5",
            stringResource(R.string.d_speed_times, 2.8f) to "2.8",
            stringResource(R.string.d_speed_times, 3f) to "3",
            stringResource(R.string.d_speed_times, 3.2f) to "3.2",
            stringResource(R.string.d_speed_times, 3.5f) to "3.5",
            stringResource(R.string.d_speed_times, 3.8f) to "3.8",
            stringResource(R.string.d_speed_times, 4f) to "4",
        ),
        onShowBottomProgressChange = {
            saveBoolean(PLAYER_SHOW_BOTTOM_PROGRESS, it)
            refreshKey++
        },
        onUseCronetChange = {
            saveBoolean(PLAYER_USE_CRONET, it)
            refreshKey++
        },
        onPlayerSpeedChange = {
            saveString(PLAYER_SPEED, it)
            refreshKey++
        },
        onLongPressSpeedChange = {
            saveString(PLAYER_LONG_PRESS_SPEED_TIMES, it)
            refreshKey++
        },
        onSlideSensitivityChange = {
            Preferences.preferenceSp.edit { putInt(PLAYER_SLIDE_SENSITIVITY, it) }
            refreshKey++
        },
    )
}

private fun buildPlayerSettingsUiState(context: Context): PlayerSettingsUiState {
    val currentSpeed = Preferences.playerSpeed
    val currentLongPressSpeed = Preferences.longPressSpeedTime
    val speedIndex = PlayerDefaults.speedArray.indexOfFirst { it == currentSpeed }
        .takeIf { it >= 0 } ?: PlayerDefaults.DEF_SPEED_INDEX
    return PlayerSettingsUiState(
        showBottomProgress = Preferences.showBottomProgress,
        useCronet = Preferences.useCronet,
        playerSpeed = currentSpeed.toString(),
        playerSpeedLabel = PlayerDefaults.speedStringArray[speedIndex],
        longPressSpeedTimes = currentLongPressSpeed.toString(),
        longPressSpeedTimesLabel = context.getString(R.string.d_speed_times, currentLongPressSpeed),
        slideSensitivity = Preferences.slideSensitivity,
        slideSensitivitySummary = toPrettySensitivityString(context, Preferences.slideSensitivity),
    )
}
