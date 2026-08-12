package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeTheme
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.SonnerToast

abstract class BaseActivity : AppCompatActivity() {

    protected open fun beforeSuperOnCreate(savedInstanceState: Bundle?) = Unit

    protected open fun onActivityCreated(savedInstanceState: Bundle?) = Unit

    final override fun onCreate(savedInstanceState: Bundle?) {
        beforeSuperOnCreate(savedInstanceState)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onActivityCreated(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            window.isNavigationBarContrastEnforced = false
    }

    override fun onResume() {
        super.onResume()
        setSecureMode(SettingsRepository.secureMode)
    }

    fun setSecureMode(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    protected fun setHanimeContent(content: @Composable () -> Unit) {
        setContent {
            val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
            val systemDensity = LocalDensity.current
            val densityScale = if (BuildConfig.DEBUG) settings.displayDensity.scale else 1f
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = systemDensity.density * densityScale,
                    fontScale = systemDensity.fontScale,
                ),
            ) {
                HanimeTheme {
                    content()
                    SonnerToast.Host()
                }
            }
        }
    }
}
