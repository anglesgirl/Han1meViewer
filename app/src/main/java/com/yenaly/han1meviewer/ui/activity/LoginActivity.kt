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
import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.ech.HyWebViewHelper
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
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
                /**
                 * 受保护域名（ECH 名单）的**所有子请求**必须在这里接管：
                 * WebView 自己的 TLS 栈无法注入 ECH，放行即等于明文暴露 SNI。
                 * 非受保护域名返回 null，保持 WebView 原行为。
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    HyWebViewHelper.intercept(request)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

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

                    // 系統代理已處理路由，WebView 直接加載，無需攔截
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
            // 直接載入登錄頁：ECH 现在在传输层（Conscrypt）生效，
            // 不再需要等本地 Go 代理就绪。
            loadUrl(loginPageUrl())
        }
    }

    /** 登录页地址：直接用當前選擇的站點 baseUrl (hanime1.me / javchu.com 等)。
     * ECH 由传输层处理，页面上看到的就是真实域名。 */
    private fun loginPageUrl(): String {
        val base = Preferences.baseUrl
        val loginPath = if (base.endsWith("/")) "login" else "/login"
        return base + loginPath
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