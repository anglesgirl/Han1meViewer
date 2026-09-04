package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.yenaly.han1meviewer.HANIME_LOGIN_URL
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.analytics.PostHogManager
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.network.WebViewEchHelper
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.ui.screen.login.LoginDialog
import com.yenaly.han1meviewer.ui.screen.login.LoginScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import kotlinx.coroutines.launch
import java.util.Locale

class LoginActivity : FrameActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var isRefreshing by mutableStateOf(true)
    private var showLoginDialog by mutableStateOf(false)
    private var isLoggingIn by mutableStateOf(false)

    override fun setUiStyle() {
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
            } else {
                GlobalToasts.show(getString(R.string.request_camera), level = GlobalToasts.ToastLevel.WARNING)
            }
        }

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val cookie = result.data?.getStringExtra("cookie")
                Log.i("LoginActivity", "扫描结果: $cookie")
                login(cookie.toString())
                setResult(RESULT_OK)
                finish()
            }
        }

        val composeView = ComposeView(this)
        setContentView(composeView)
        composeView.setContent {
            HanimeTheme {
                if (showLoginDialog) {
                    LoginDialog(
                        isLoggingIn = isLoggingIn,
                        onDismiss = { showLoginDialog = false },
                        onLogin = { username, password -> handleLogin(username, password) },
                    )
                }
                LoginScreen(
                    isRefreshing = isRefreshing,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { webView?.loadUrl(HANIME_LOGIN_URL) },
                    onShowLoginDialog = { showLoginDialog = true },
                    onOpenQrScanner = { openQrScanner() },
                    webViewFactory = { createWebView() },
                )
            }
        }
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            webView = this
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (request != null) {
                        WebViewEchHelper.intercept(request)?.let { return it }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    isRefreshing = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val raw = request.url.toString()
                    val real = if (raw.startsWith("http://127.0.0.1:23333/http")) raw.substringAfter("http://127.0.0.1:23333/") else raw
                    val isSameUrl = HANIME_URL.any { real.contains(it.removeSuffix("/")) }
                    if (request.isRedirect && isSameUrl) {
                        val cookieManager = CookieManager.getInstance().getCookie(real) ?: CookieManager.getInstance().getCookie(request.url.host)
                        Log.d("login_cookie", cookieManager.toString())
                        if (!cookieManager.isNullOrEmpty()) {
                            login(cookieManager)
                            setResult(RESULT_OK)
                            finish()
                            return true
                        }
                    }
                    // 代理地址让 WebView 自行加载
                    if (raw.startsWith("http://127.0.0.1:23333/")) return false
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true && !isDestroyed && !isFinishing) {
                        isRefreshing = false
                        showLoginDialog = true
                    }
                }
            }
            loadUrl(com.yenaly.han1meviewer.logic.network.EchProxyServer.proxyUrl(HANIME_LOGIN_URL))
        }
    }

    private fun openQrScanner() {
        scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.removeAllViews()
        webView?.destroy()
    }

    private fun handleLogin(username: String, password: String) {
        isLoggingIn = true
        lifecycleScope.launch {
            NetworkRepo.login(username, password).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit

                    is WebsiteState.Error -> {
                        PostHogManager.track("login_fail")
                        isLoggingIn = false
                        state.throwable.printStackTrace()
                        if (state.throwable is IllegalStateException) {
                            GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                        } else {
                            GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                        }
                    }

                    is WebsiteState.Success -> {
                        PostHogManager.track("login")
                        login(state.info)
                        setResult(RESULT_OK)
                        showLoginDialog = false
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

        val config = Configuration(context.resources.configuration)
        config.setLocale(newLocale)
        return context.createConfigurationContext(config)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyAppLocale(newBase))
    }
}
