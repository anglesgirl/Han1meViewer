package io.github.daisukikaffuchino.han1meviewer.util

import android.content.Context
import android.content.SharedPreferences

val Context.defaultSharedPreferences: SharedPreferences
    get() = applicationContext.getSharedPreferences(
        "${applicationContext.packageName}_preferences",
        Context.MODE_PRIVATE,
    )
