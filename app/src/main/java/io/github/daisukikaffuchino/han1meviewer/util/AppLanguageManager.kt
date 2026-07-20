package io.github.daisukikaffuchino.han1meviewer.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import io.github.daisukikaffuchino.han1meviewer.logic.model.AppLanguage

object AppLanguageManager {
    const val PREFERENCE_KEY = "app_language"

    fun current(context: Context): AppLanguage {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return AppLanguage.fromPreference(
            preferences.getString(PREFERENCE_KEY, AppLanguage.SYSTEM_VALUE)
        )
    }

    fun applyStoredLanguage(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val language = current(context)
        if (preferences.getString(PREFERENCE_KEY, null) != language.preferenceValue) {
            preferences.edit { putString(PREFERENCE_KEY, language.preferenceValue) }
        }
        setAppLanguage(language)
    }

    fun select(context: Context, language: AppLanguage) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREFERENCE_KEY, language.preferenceValue)
        }
        setAppLanguage(language)
    }

    fun setAppLanguage(language: AppLanguage) {
        val locales = language.code?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
