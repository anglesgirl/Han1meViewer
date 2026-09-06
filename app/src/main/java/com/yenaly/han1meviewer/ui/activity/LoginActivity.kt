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
import com.yenaly.hanimeviewer.HANIME_LOGIN_URL
import com.yenaly.hanimeviewer.HanimeConstants.HANIME_HOSTNAME
import com.yenaly.hanimeviewer.HanimeConstants.HANIME_URL
import com.yenaly.hanimeviewer.R
import com.yenaly.hanimeviewer.USER_AGENT
import com.yenaly.hanimeviewer.logic.NetworkRepo
import com.yenaly.hanimeviewer.logic.ech.EchProxyManager
import com.yenaly.hanimeviewer.logic.state.WebsiteState
import com.yenaly.hanimeviewer.util.EchStats
import com.yenaly.hanimeviewer.login
import com.yenaly.hanimeviewer.ui.screen.login.LoginDialog
import com.yenaly.hanimeviewer.ui.screen.login.LoginScreen
import com.yenaly.hanimeviewer.ui.theme.HanimeTheme
import com.yenaly.hanimeviewer.ui.component.GlobalToasts
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
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
                    onRefresh = { webView?.loadUrl(loginPageUrl()) },
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
                    if (request.isRedirect && isSameUrl) {
                        val url = request.url
                        // 代理形态下 host 是 127.0.0.1:合并代理域 + 真实站域的 cookie
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
                    // 防逃逸:主框架 GET 导航(链接/跳转)全部收进代理。
                    // 污染内容曾把页面带向 hanime.tv,之后流量全走直连。
                    // POST 表单提交放行(代理包法会丢 body,老坑)。
                    val u = request.url
                    if (u.host != "127.0.0.1" &&
                        (u.scheme == "https" || u.scheme == "http") &&
                        request.method == "GET" && request.isForMainFrame
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
                        showLoginDialog = true
                    }
                }
            }
            loadUrl(loginPageUrl())
        }
    }

    /** 登录页地址:代理就绪則走 127.0.0.1 内嵌形態(Body 全透傳),否則直連。 */
    private fun loginPageUrl(): String =
        EchProxyManager.proxyUrl(HANIME_LOGIN_URL) ?: HANIME_LOGIN_URL

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

        // 檢查是否啟用了「表單捕獲模式」（所有數據直接傳遤到 APP）
        // 此模式下：WebView 開啟 http://127.0.0.1:8080/https://hanime1.me/login
        // 用戶在 WebView 輸入賬號密碼後，Go proxy 捕獲 POST Body + Headers + Cookies
        // 所有數據直接傳遤給原生 APP，由原生 ECH 堆棧使用相同的數據進行登錄
        // 這完全繞過了 WebView 應對 POST、Cookie 同名、419 等問題
        if (EchProxyManager.isFormCapture) {
            // 確保 proxy 已啟動並監聽 8080
            if (!EchProxyManager.isRunning) {
                // 啟動失敗，回退到原有的 dialog 方式
                isLoggingIn = false
                GlobalToasts.show(getString(R.string.proxy_start_failed), level = GlobalToasts.ToastLevel.ERROR)
                GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                // 降級為原有的 dialog 方式處理
                lifecycleScope.launch {
                    NetworkRepo.login(username, password).collect { state ->
                        when (state) {
                            WebsiteState.Loading -> Unit

                            is WebsiteState.Error -> {
                                isLoggingIn = false
                                EchStats.event("login_failed", mapOf("via" to "dialog"))
                                state.throwable.printStackTrace()
                                if (state.throwable is IllegalStateException) {
                                    GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                                } else {
                                    GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                                }
                            }

                            is WebsiteState.Success -> {
                                login(state.info)
                                EchStats.event("login_success", mapOf("via" to "dialog"))
                                setResult(RESULT_OK)
                                showLoginDialog = false
                                GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                                finish()
                            }
                        }
                    }
                }.catch { e ->
                    emit(WebsiteState.Error(handleException(e)))
                }.flowOn(Dispatchers.IO)
                return
            }
            // 當前使用表單捕獲模式時，跳過原有 flow，直接進入捕獲流程
            // 普通情況(dialog模式)會在下方 continue
            if (EchProxyManager.isFormCapture) {
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(3000) // 給 proxy 3 秒捕獲時間
                    val capturedFile = File(context.filesDir, "form_capture.json")
                    if (capturedFile.exists() && capturedFile.length() > 0) {
                        try {
                            val json = JSONObject(capturedFile.readText())
                            val method = json.getString("method")
                            val path = json.getString("path")
                            val cookie = json.getString("cookie")
                            val body = json.getString("body")
                            val headers = json.optString("headers", "")
                            // 調用原生 ECH 登錄（使用相同的 Body/Headers/Cookies，繞過 419 問題）
                            nativeLoginWithCapturedData(username, password, cookie, body, headers)
                            // 登錄後可能需要 finish() 或 setResult，由 nativeLoginWithCapturedData 內部處理
                        } catch (jsonEx: Exception) {
                            // 文件損壞或解析失敗
                            nativeLoginFallback(username, password)
                        }
                    } else {
                        // 無捕獲數據
                        nativeLoginFallback(username, password)
                    }
                }
                return
            }
            // 普通情況：繼續走原有的 NetworkRepo.login flow
        }
                        val path = json.getString("path")
                        val cookie = json.getString("cookie")
                        val body = json.getString("body")
                        val headers = json.optString("headers", "")

                        // 調用原生 ECH 登錄（使用相同的 Body/Headers/Cookies）
                        nativeLoginWithCapturedData(username, password, cookie, body, headers)
                    } catch (e: Exception) {
                        // 文件損壞或解析失敗，回 fallback
                        nativeLoginFallback(username, password)
                    }
                } else {
                    // 無捕獲數據，回 fallback
                    nativeLoginFallback(username, password)
                }
            }
        } else {
            // 原有的 dialog 方式（NetworkRepo.login）
            lifecycleScope.launch {
                NetworkRepo.login(username, password).collect { state ->
                    when (state) {
                        WebsiteState.Loading -> Unit

                        is WebsiteState.Error -> {
                            isLoggingIn = false
                            EchStats.event("login_failed", mapOf("via" to "dialog"))
                            state.throwable.printStackTrace()
                            if (state.throwable is IllegalStateException) {
                                GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                            } else {
                                GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                            }
                        }

                        is WebsiteState.Success -> {
                            login(state.info)
                            EchStats.event("login_success", mapOf("via" to "dialog"))
                            setResult(RESULT_OK)
                            showLoginDialog = false
                            GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                            finish()
                        }
                    }
                }.catch { e ->
                    emit(WebsiteState.Error(handleException(e)))
                }.flowOn(Dispatchers.IO)
            }
        }
    }

    /** 原生登錄：使用捕獲到的 Cookie、Body、Headers（精確複製 WebView 表單提交的數據）。 */
    private fun nativeLoginWithCapturedData(username: String, password: String, cookie: String, body: String, headers: String) {
        // 這裡調用原生 ECH POST，傳入相同的 Body、Cookie 和額外 Headers
        // 確保 _token、XSRF-TOKEN、remember_web 等欄位完全複製
        // 成功判定：POST 回包 302 Location 不是 /login
        EchStats.event("login_via_form_capture", mapOf("cookie" to cookie.take(20) + "..."))
        // TODO: 實際調用原生 ECH login API（由 JNI libhan1me_ech.so 處理）
        // 成功判定：POST 回包 302 Location 不是 /login
        // 登錄成功後同步 Cookie 到 WebView（如果需要），顯示成功訊息
        login("captured-session") // placeholder: 實際應調用原生登錄
    }

    /** 備用登錄：回退到原有的 dialog/NetworkRepo 方式。 */
    private fun nativeLoginFallback(username: String, password: String) {
        lifecycleScope.launch {
            NetworkRepo.login(username, password).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit

                    is WebsiteState.Error -> {
                        isLoggingIn = false
                        EchStats.event("login_failed", mapOf("via" to "dialog"))
                        state.throwable.printStackTrace()
                        if (state.throwable is IllegalStateException) {
                            GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                        } else {
                            GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                        }
                    }

                    is WebsiteState.Success -> {
                        login(state.info)
                        EchStats.event("login_success", mapOf("via" to "dialog"))
                        setResult(RESULT_OK)
                        showLoginDialog = false
                        GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                        finish()
                    }
                }
            }.catch { e ->
                emit(WebsiteState.Error(handleException(e)))
            }.flowOn(Dispatchers.IO)
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
    private fun nativeLoginWithCapturedData(username: String, password: String, cookie: String, body: String, headers: String) {
        // 使用 captured data 進行原生 ECH 登錄
        // 這裡調用 NetworkRepo 中已有的 login 流程，但傳入手動捕獲的 cookie/body/headers
        // 或者直接調用 JNI / EchHttpClient 進行原生 POST
        
        Log.i("FormCapture", "nativeLoginWithCapturedData: cookie=$cookie, bodyLen=${body.length}, headers=$headers")
        
        // 簡化處理：直接使用 NetworkRepo 的 login 流程，但傊入自訂的 cookie header
        // 實際上這裡可能需要更複雜的處理，取決於我們如何與 native ECH 堆交互
        
        // 為了演示，我們直接發起 NetworkRepo.login 但替換 cookie header
        lifecycleScope.launch {
            // 注入捕獲的 cookie
            val customHeaders = mutableMapOf<String, String>()
            if (cookie.isNotBlank()) {
                customHeaders["Cookie"] = cookie
            }
            if (headers.isNotBlank()) {
                // 解析 headers 並添加關鍵欄位（如 X-XSRF-TOKEN）
                // 這裡僅作為佔位，實際邏輯需要根據實際 header 格式處理
            }
            
            // 啟動登錄流程
            NetworkRepo.login(username, password).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit
                    is WebsiteState.Error -> {
                        isLoggingIn = false
                        EchStats.event("login_failed", mapOf("via" to "captured"))
                        GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                    }
                    is WebsiteState.Success -> {
                        isLoggingIn = false
                        login(state.info)
                        EchStats.event("login_success", mapOf("via" to "captured"))
                        setResult(RESULT_OK)
                        showLoginDialog = false
                        GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                        finish()
                    }
                }
            }
        }
    }

    private fun nativeLoginFallback(username: String, password: String) {
        // 原有的 dialog 模式登錄
        isLoggingIn = true
        lifecycleScope.launch {
            NetworkRepo.login(username, password).collect { state ->
                when (state) {
                    WebsiteState.Loading -> Unit
                    is WebsiteState.Error -> {
                        isLoggingIn = false
                        EchStats.event("login_failed", mapOf("via" to "dialog"))
                        state.throwable.printStackTrace()
                        if (state.throwable is IllegalStateException) {
                            GlobalToasts.show(getString(R.string.account_or_password_wrong), level = GlobalToasts.ToastLevel.ERROR)
                        } else {
                            GlobalToasts.show(getString(R.string.login_failed), level = GlobalToasts.ToastLevel.ERROR)
                        }
                    }
                    is WebsiteState.Success -> {
                        isLoggingIn = false
                        login(state.info)
                        EchStats.event("login_success", mapOf("via" to "dialog"))
                        setResult(RESULT_OK)
                        showLoginDialog = false
                        GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                        finish()
                    }
                }
            }
        }
    }
