package io.github.daisukikaffuchino.han1meviewer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import io.github.daisukikaffuchino.han1meviewer.Preferences

@Composable
fun HanimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (val preset = ThemeColorPreset.fromKey(Preferences.themeColor)) {
        ThemeColorPreset.SYSTEM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicColorScheme(darkTheme)
            } else {
                ThemeColorPreset.DEFAULT.colorScheme(darkTheme)
            }
        }

        else -> preset.colorScheme(darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(),
        content = content,
    )
}
