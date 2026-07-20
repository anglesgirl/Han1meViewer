package io.github.daisukikaffuchino.utils

import android.app.Activity
import android.content.Intent
import android.os.Bundle

inline fun <reified T : Activity> Activity.startActivity(
    flag: Int? = null,
    extra: Bundle? = null,
) {
    startActivity(Intent(this, T::class.java).apply {
        flag?.let { flags = it }
        extra?.let { putExtras(it) }
    })
}
