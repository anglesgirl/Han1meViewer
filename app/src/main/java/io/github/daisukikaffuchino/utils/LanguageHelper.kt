package io.github.daisukikaffuchino.utils

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

object LanguageHelper {
    val preferredLanguage: Locale
        get() = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}
