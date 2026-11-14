package com.yenaly.han1meviewer.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.component.ChoiceDialog
import com.yenaly.han1meviewer.ui.component.SettingNavigationItem
import com.yenaly.han1meviewer.ui.component.SettingSliderItem
import com.yenaly.han1meviewer.ui.component.SettingSwitchItem
import com.yenaly.han1meviewer.ui.component.lazy.LazyColumn
import com.yenaly.han1meviewer.ui.preview.ComponentPreview

data class PlayerSettingsUiState(
    val showBottomProgress: Boolean,
    val useCronet: Boolean,
    val playerSpeed: String,
    val playerSpeedLabel: String,
    val longPressSpeedTimes: String,
    val longPressSpeedTimesLabel: String,
    val slideSensitivity: Int,
    val slideSensitivitySummary: String,
)

private enum class PlayerChoiceDialog {
    Speed,
    LongPressSpeed,
}

@Composable
fun PlayerSettingsScreen(
    state: PlayerSettingsUiState,
    speedOptions: List<Pair<String, String>>,
    longPressSpeedOptions: List<Pair<String, String>>,
    onShowBottomProgressChange: (Boolean) -> Unit,
    onUseCronetChange: (Boolean) -> Unit,
    onPlayerSpeedChange: (String) -> Unit,
    onLongPressSpeedChange: (String) -> Unit,
    onSlideSensitivityChange: (Int) -> Unit,
) {
    var activeDialog by rememberSaveable { mutableStateOf<PlayerChoiceDialog?>(null) }

    ChoiceDialog(
        visible = activeDialog == PlayerChoiceDialog.Speed,
        title = stringResource(R.string.default_playback_speed),
        options = speedOptions,
        selectedValue = state.playerSpeed,
        onDismiss = { activeDialog = null },
        onSelect = {
            activeDialog = null
            onPlayerSpeedChange(it)
        },
    )

    ChoiceDialog(
        visible = activeDialog == PlayerChoiceDialog.LongPressSpeed,
        title = stringResource(R.string.long_press_speed_multiplier),
        options = longPressSpeedOptions,
        selectedValue = state.longPressSpeedTimes,
        onDismiss = { activeDialog = null },
        onSelect = {
            activeDialog = null
            onLongPressSpeedChange(it)
        },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SettingSwitchItem(
                title = stringResource(R.string.show_bottom_progress),
                checked = state.showBottomProgress,
                iconRes = R.drawable.baseline_seek_24,
                onCheckedChange = onShowBottomProgressChange,
            )
        }

        item {
            SettingSwitchItem(
                title = stringResource(R.string.use_cronet),
                summary = stringResource(R.string.use_cronet_summary),
                checked = state.useCronet,
                iconRes = R.drawable.baseline_atomic_24,
                onCheckedChange = onUseCronetChange,
            )
        }

        item {
            SettingNavigationItem(
                title = stringResource(R.string.default_playback_speed),
                valueText = state.playerSpeedLabel,
                iconRes = R.drawable.baseline_speed2_24,
                onClick = { activeDialog = PlayerChoiceDialog.Speed },
            )
        }

        item {
            SettingNavigationItem(
                title = stringResource(R.string.long_press_speed_multiplier),
                valueText = state.longPressSpeedTimesLabel,
                iconRes = R.drawable.baseline_touch_24,
                onClick = { activeDialog = PlayerChoiceDialog.LongPressSpeed },
            )
        }

        item {
            SettingSliderItem(
                title = stringResource(R.string.slide_sensitivity),
                summary = state.slideSensitivitySummary,
                value = state.slideSensitivity,
                valueRange = 1..9,
                iconRes = R.drawable.baseline_speed_24,
                onValueChange = onSlideSensitivityChange,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun PlayerSettingsScreenPreview() {
    ComponentPreview {
        PlayerSettingsScreen(
            state = PlayerSettingsUiState(
                showBottomProgress = true,
                useCronet = false,
                playerSpeed = "1.0",
                playerSpeedLabel = "1.0x",
                longPressSpeedTimes = "2.5",
                longPressSpeedTimesLabel = "2.5倍",
                slideSensitivity = 5,
                slideSensitivitySummary = stringResource(
                    R.string.current_slide_sensitivity,
                    stringResource(R.string.moderate),
                ),
            ),
            speedOptions = listOf("1.0x" to "1.0", "1.25x" to "1.25", "1.5x" to "1.5"),
            longPressSpeedOptions = listOf("2.0倍" to "2", "2.5倍" to "2.5", "3.0倍" to "3"),
            onShowBottomProgressChange = {},
            onUseCronetChange = {},
            onPlayerSpeedChange = {},
            onLongPressSpeedChange = {},
            onSlideSensitivityChange = {},
        )
    }
}
