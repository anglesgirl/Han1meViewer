package io.github.daisukikaffuchino.han1meviewer.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    visible: Boolean = true,
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(
                horizontal = HanimeDefaults.settingsItemVerticalPadding,
            ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(
                    horizontal = HanimeDefaults.screenVerticalPadding,
                    vertical = HanimeDefaults.settingsItemPadding,
                ),
            )
            options.forEach { (label, value) ->
                val selected = selectedValue == value
                ListItem(
                    onClick = { onSelect(value) },
                    selected = selected,
                    leadingContent = {
                        RadioButton(selected = selected, onClick = null)
                    },
                    content = { Text(label) },
                    colors = ListItemDefaults.colors(
                        containerColor = BottomSheetDefaults.ContainerColor,
                    ),
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ChoiceDialogPreview() {
    ComponentPreview {
        ChoiceDialog(
            title = "选择播放器",
            options = listOf("ExoPlayer" to "exo", "MPV" to "mpv"),
            selectedValue = "exo",
            onDismiss = {},
            onSelect = {},
        )
    }
}
