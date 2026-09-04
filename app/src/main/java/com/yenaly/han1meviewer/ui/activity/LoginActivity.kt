package com.yenaly.han1meviewer.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.analytics.PostHogManager
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.han1meviewer.ui.screen.login.LoginScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import kotlinx.coroutines.launch
import java.util.Locale

class LoginActivity : FrameActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var isRefreshing by mutableStateOf(false)
    private var isLoggingIn by mutableStateOf(false)

    override fun setUiStyle() {
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val cookie = result.data?.getStringExtra("cookie") ?: return@registerForActivityResult
                Log.i("LoginActivity", "扫描结果已接收")
                login(cookie)
                setResult(RESULT_OK)
                finish()
            }
        }

        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HanimeTheme {
                LoginScreen(
                    isRefreshing = isRefreshing,
                    isLoggingIn = isLoggingIn,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = {},
                    onLogin = { email, password -> handleLogin(email, password) },
                    onOpenQrScanner = { openQrScanner() },
                )
            }
        }
    }

    private fun openQrScanner() {
        scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
    }

    private fun handleLogin(email: String, password: String) {
        if (isLoggingIn) return
        isLoggingIn = true
        lifecycleScope.launch {
            NetworkRepo.login(email, password).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit
                    is WebsiteState.Error -> {
                        PostHogManager.track("login_fail")
                        isLoggingIn = false
                        state.throwable.printStackTrace()
                        GlobalToasts.show(
                            getString(if (state.throwable is IllegalStateException) R.string.account_or_password_wrong else R.string.login_failed),
                            level = GlobalToasts.ToastLevel.ERROR,
                        )
                    }
                    is WebsiteState.Success -> {
                        PostHogManager.track("login")
                        login(state.info)
                        setResult(RESULT_OK)
                        GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                        finish()
                    }
                }
            }
        }
    }

    private fun applyAppLocale(context: Context): Context {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lang = prefs.getString("app_language", "system") ?: "system"
        val newLocale = when (lang) {
            "zh-rCN" -> Locale.SIMPLIFIED_CHINESE
            "zh" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            "ja" -> Locale.JAPANESE
            else -> Resources.getSystem().configuration.locales.get(0)
        }
        Locale.setDefault(newLocale)
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(newLocale) })
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyAppLocale(newBase))
    }
}
