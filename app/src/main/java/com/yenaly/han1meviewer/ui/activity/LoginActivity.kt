package com.yenaly.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.yenaly.han1meviewer.HanimeConstants
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.gecko.GeckoEngine
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.login
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import com.yenaly.han1meviewer.ui.screen.login.LoginDialog
import com.yenaly.han1meviewer.ui.screen.login.LoginScreen
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.yenaly_libs.base.frame.FrameActivity
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.Locale

class LoginActivity : FrameActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var isRefreshing by mutableStateOf(true)
    private var showLoginDialog by mutableStateOf(false)
    private var isLoggingIn by mutableStateOf(false)
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var hasHandledLogin = false

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
                    onRefresh = { geckoSession?.loadUri(HANIME_LOGIN_URL) },
                    onShowLoginDialog = { showLoginDialog = true },
                    onOpenQrScanner = { openQrScanner() },
                    webViewFactory = { createGeckoViewWrapper() },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createGeckoViewWrapper(): android.view.View {
        val context = this
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val gv = GeckoView(context)
        geckoView = gv
        container.addView(gv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val runtime = GeckoEngine.getRuntime(context)
        val session = GeckoSession()
        geckoSession = session

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                Log.i("GeckoLogin", "location=$url")
                isRefreshing = false
                // 检测登录成功：从 login 跳到非 login 的 hanime 页面且带重定向特征
                if (!hasHandledLogin && url != null) {
                    val isHanime = HanimeConstants.HANIME_HOSTNAME.any { url.contains(it) }
                    val isLogin = url.contains("/login")
                    if (isHanime && !isLogin) {
                        // 延迟取 cookie，避免 Set-Cookie 还未写入
                        session.loadUri("javascript:prompt('HANIME_COOKIE:'+document.cookie)")
                    }
                }
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                Log.i("GeckoLogin", "loadRequest uri=${request.uri} redirect=${request.isRedirect}")
                if (!hasHandledLogin && request.isRedirect) {
                    val uri = request.uri
                    val isHanime = HanimeConstants.HANIME_HOSTNAME.any { uri.contains(it) }
                    val isLogin = uri.contains("/login")
                    if (isHanime && !isLogin) {
                        // 触发 JS 取 cookie
                        try {
                            session.loadUri("javascript:prompt('HANIME_COOKIE:'+document.cookie)")
                        } catch (_: Exception) {}
                    }
                }
                return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.ALLOW)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                Log.e("GeckoLogin", "loadError uri=$uri code=${error.code} category=${error.category}")
                if (uri != null && uri.contains("hanime")) {
                    isRefreshing = false
                    // 仅在主帧错误时弹对话框，避免子资源错误误弹
                    runOnUiThread { showLoginDialog = true }
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                Log.i("GeckoLogin", "pageStart $url")
                isRefreshing = true
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                Log.i("GeckoLogin", "pageStop success=$success")
                isRefreshing = false
            }
            override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {
                Log.i("GeckoLogin", "security ${info.origin} secure=${info.isSecure}")
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val msg = prompt.message ?: ""
                Log.i("GeckoLogin", "prompt message=$msg")
                if (msg.startsWith("HANIME_COOKIE:")) {
                    val cookie = msg.removePrefix("HANIME_COOKIE:")
                    Log.i("GeckoLogin", "captured cookie len=${cookie.length} value=$cookie")
                    if (cookie.isNotBlank() && !hasHandledLogin) {
                        hasHandledLogin = true
                        // 必须包含 hanime1_session 才算有效登录，否则可能是匿名 cookie
                        if (cookie.contains("hanime1_session") || cookie.contains("remember_web")) {
                            runOnUiThread {
                                login(cookie)
                                setResult(RESULT_OK)
                                GlobalToasts.show(getString(R.string.login_success), level = GlobalToasts.ToastLevel.SUCCESS)
                                finish()
                            }
                        } else {
                            Log.w("GeckoLogin", "cookie without session, ignore")
                            hasHandledLogin = false
                        }
                    }
                    return GeckoResult.fromValue(prompt.confirm(""))
                }
                return GeckoResult.fromValue(prompt.confirm(prompt.defaultValue ?: ""))
            }
            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // 兜底：alert 也可能被用于传递 cookie
                val msg = prompt.message ?: ""
                if (msg.startsWith("HANIME_COOKIE:")) {
                    val cookie = msg.removePrefix("HANIME_COOKIE:")
                    if (cookie.isNotBlank() && !hasHandledLogin) {
                        hasHandledLogin = true
                        runOnUiThread {
                            login(cookie)
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
        }

        // 保留 UA 与上游一致，便于服务端识别
        try {
            session.settings.userAgentOverride = USER_AGENT
        } catch (_: Exception) {}

        session.open(runtime)
        gv.setSession(session)
        // 延迟加载，确保 session 已 attach
        gv.post { session.loadUri(HANIME_LOGIN_URL) }

        return container
    }

    // 兼容旧 LoginScreen 的 webViewFactory 返回 View，GeckoView 已在 FrameLayout 中
    // 若 Compose 侧仍调用 webView?.loadUrl，改为 session.loadUri
    private var webView: android.webkit.WebView? = null

    private fun openQrScanner() {
        scannerLauncher.launch(Intent(this, ManualInputCookiesActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && geckoSession != null) {
            try {
                // GeckoView 返回由 session.goBack 处理
                geckoSession?.goBack()
                return true
            } catch (_: Exception) {}
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { geckoSession?.close() } catch (_: Exception) {}
        geckoSession = null
        geckoView?.releaseSession()
        geckoView = null
        super.onDestroy()
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
