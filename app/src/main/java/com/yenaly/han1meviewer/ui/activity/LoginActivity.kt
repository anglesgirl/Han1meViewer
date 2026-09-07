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
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import com.yenaly.han1meviewer.logic.network.HCookieJar
import com.yenaly.han1meviewer.logic.network.ServiceCreator
import com.yenaly.han1meviewer.ui.screen.login.LoginScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.han1meviewer.util.EchStats
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import okhttp3.Request
import kotlinx.coroutines.launch
import java.util.Locale

class LoginActivity : FrameActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var isRefreshing by mutableStateOf(true)
    private var showLoginDialog by mutableStateOf(false) // 保留兼容，實際不再使用
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
                // 直接顯示 LoginScreen，不再彈 LoginDialog
                LoginScreen(
                    isRefreshing = isRefreshing,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { webView?.loadUrl(loginPageUrl()) },
                    onShowLoginDialog = { /* 不再使用 dialog，保留兼容 */ },
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
            // 進入登錄頁前清空舊 Cookie，避免污染
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    isRefreshing = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // 代理内嵌形態还原真实地址再判定:
                    // http://127.0.0.1:port/https://hanime1.me/ → https://hanime1.me/
                    val rawU = request.url
                    val effective = if (rawU.host == "127.0.0.1" &&
                        (rawU.path?.startsWith("/http") == true)
                    ) {
                        rawU.path!!.removePrefix("/")
                    } else {
                        rawU.toString()
                    }
                    val isSameUrl = HANIME_URL.contains(effective)

                    // 登錄成功：302 重定向到首頁（非 /login）
                    if (request.isRedirect && isSameUrl) {
                        val url = request.url
                        val cm = CookieManager.getInstance()
                        val parts = mutableListOf<String>()
                        cm.getCookie(url.host)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                        HANIME_HOSTNAME.forEach { h ->
                            cm.getCookie(h)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                        }
                        val cookieManager = parts.joinToString("; ")
                        Log.d("login_cookie", cookieManager)
                        login(cookieManager)
                        EchStats.event("login_success", mapOf("via" to "webview"))
                        setResult(RESULT_OK)
                        finish()
                        return true
                    }

                    // 關鍵：攔截所有直連站點請求 → 強制走代理
                    // POST 表單提交放行(代理模式下 POST 給 127.0.0.1 由代理轉發，Body 全透傳)
                    val u = request.url
                    val isLoginPage = u.path?.contains("/login") == true
                    val isSiteHost = HANIME_HOSTNAME.any { u.host == it || u.host.endsWith(".$it") }
                    
                    if ((isSiteHost || isLoginPage) && u.host != "127.0.0.1" &&
                        (u.scheme == "https" || u.scheme == "http") &&
                        (request.method == "GET" || request.method == "POST") && request.isForMainFrame
                    ) {
                        val proxied = EchProxyManager.proxyUrl(u.toString())
                        if (proxied != null) {
                            view.loadUrl(proxied)
                            return true
                        }
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
                        GlobalToasts.show(getString(R.string.load_failed_retry), level = GlobalToasts.ToastLevel.ERROR)
                    }
                }
            }
            // 等待代理就緒再載入登錄頁
            lifecycleScope.launch {
                while (!EchProxyManager.isRunning) {
                    kotlinx.coroutines.delay(200)
                }
                loadUrl(loginPageUrl())
            }
        }
    }

    /** 登录页地址:代理就绪則走 127.0.0.1 内嵌形態(Body 全透傳),否則直連。
     * 使用當前選擇的站點 baseUrl (hanime1.me / javchu.com 等)。 */
    private fun loginPageUrl(): String {
        val base = Preferences.baseUrl
        val loginUrl = if (base.endsWith("/")) base + "login" else base + "/login"
        return EchProxyManager.proxyUrl(loginUrl) ?: loginUrl
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