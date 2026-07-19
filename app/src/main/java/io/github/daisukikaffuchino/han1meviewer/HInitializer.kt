package io.github.daisukikaffuchino.han1meviewer

import android.content.Context
import com.yenaly.yenaly_libs.base.YenalyInitializer

class HInitializer : YenalyInitializer() {
    override fun create(context: Context) {
        super.create(context)
        Thread.setDefaultUncaughtExceptionHandler(HCrashHandler)
    }
}
