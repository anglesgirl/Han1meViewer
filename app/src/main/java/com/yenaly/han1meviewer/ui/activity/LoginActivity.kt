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
import com.yenaly.han1meviewer.logic.NetworkRepo
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

            // 非 GET 传输桥：登录表单提交 / 页面里的 fetch、XHR 交给原生走 ECH。
            // 必须在 loadUrl 之前挂载；只对受保护域名生效（原生侧与 JS 侧都判一次）
            com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper.installWebView(this)
            // 表单被原生代发后 WebView 不会自己跳转，原 shouldOverrideUrlLoading(isRedirect)
            // 那条判成功的路永远不触发 —— 这里接上同一套登录完成逻辑（两处调同一个 login()）
            com.yenaly.han1meviewer.logic.network.ech.EchWebBridge.onFormLoginSuccess = { cookie ->
                isLoggingIn = false
                login(cookie)
                setResult(RESULT_OK)
                finish()
            }

            webViewClient = object : WebViewClient() {
                // WebView 的子请求在这里接管，交给 OkHttp（Conscrypt + ECH）去发。
                // 这是"WebView 用上 ECH"的唯一入口 —— 换掉 Go 本地反代后不再需要任何转发层。
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper
                        .intercept(request)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    super.onPageCommitVisible(view, url)
                    // 尽早注入（页面还在渲染时），别等 onPageFinished —— 页面自己的 XHR 可能更早发出
                    com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper.injectBridge(view, url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    isRefreshing = false
                    // 页面加载完注入非 GET 传输桥（登录表单提交/页面 fetch、XHR 走原生 ECH）
                    com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper.injectBridge(view, url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val isSameUrl = HANIME_URL.contains(request.url.toString())
                    if (request.isRedirect && isSameUrl) {
                        val url = request.url
                        val cookieManager = CookieManager.getInstance().getCookie(url.host)
                        Log.d("login_cookie", cookieManager.toString())
                        login(cookieManager)
                        setResult(RESULT_OK)
                        finish()
                        return true
                    }
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
            loadUrl(HANIME_LOGIN_URL)
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
        // 别让静态回调持有已销毁的 Activity
        com.yenaly.han1meviewer.logic.network.ech.EchWebBridge.onFormLoginSuccess = null
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
                        isLoggingIn = false
                        state.throwable.printStackTrace()
                        if (state.throwable is IllegalStateException) {
                            GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                        } else {
                            GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                        }
                    }

                    is WebsiteState.Success -> {
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
