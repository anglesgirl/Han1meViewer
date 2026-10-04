package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.USER_AGENT
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 登录 POST 的 JS 桥（参考 co3 的 EchWebViewManager.Bridge）。
 *
 * 为什么必须这么做：Android 的 `shouldInterceptRequest` **拿不到 POST body**，
 * 所以登录 POST 只能由原生侧代发；而只有原生侧代发才能走 ECH 引擎。
 *
 * 流程：
 * 1. 注入的 JS 劫持登录表单 submit，提取字段后调 `postLogin(action, body)`
 * 2. 原生在后台线程经 ECH 发送 POST（不跟随重定向）
 * 3. Set-Cookie 写回 CookieManager（真实域名）
 * 4. 成功时直接回调 LoginResultCallback.onLoginSuccess（免去等 WebView 加载跳转页的几秒）；
 *    失败时 UI 线程 `webView.loadUrl()` 回到登录页
 */
class EchWebBridge(
    private val webView: WebView,
    private val callback: LoginResultCallback? = null,
) {

    /** 登录结果回调：POST 成功时直接回传会话 cookie，无需等页面加载 */
    interface LoginResultCallback {
        fun onLoginSuccess(cookie: String)
    }

    private val TAG = "EchWebBridge"

    /**
     * JS 注入的登录表单劫持脚本（幂等：window.__hyLoginHijack）。
     * 只劫持 action 含 /login 的表单提交。
     */
    fun injectLoginHijackScript(): String = """
        (function(){
          if (window.__hyLoginHijack) return;
          window.__hyLoginHijack = 1;
          document.addEventListener('submit', function(e){
            var f = e.target;
            if (!f || !f.getAttribute) return;
            var action = f.getAttribute('action') || f.action || '';
            if (action.indexOf('/login') < 0) return;
            e.preventDefault();
            e.stopPropagation();
            var parts = [];
            var els = f.elements || [];
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (!el || !el.name || el.disabled) continue;
              if ((el.type === 'checkbox' || el.type === 'radio') && !el.checked) continue;
              parts.push(encodeURIComponent(el.name) + '=' + encodeURIComponent(el.value === undefined || el.value === null ? '' : el.value));
            }
            var body = parts.join('&');
            try {
              HyBridge.postLogin(action, body);
            } catch (err) {}
          }, true);
        })();
    """.trimIndent()

    @JavascriptInterface
    fun postLogin(url: String, body: String) {
        // action 可能是相对路径，转绝对
        val absoluteUrl = if (url.startsWith("http")) url
        else {
            val base = HANIME_URL.firstOrNull { isTargetUrl(it) }
                ?: "https://hanime1.me/"
            base.trimEnd('/') + "/" + url.trimStart('/')
        }
        Log.i(TAG, "postLogin $absoluteUrl bodyLen=${body.length} " +
            "hasToken=${body.contains("_token")}")
        Thread {
            try {
                doPostLogin(absoluteUrl, body)
            } catch (e: Exception) {
                Log.e(TAG, "postLogin failed: ${e.message}")
                webView.post { runCatching { webView.loadUrl(absoluteUrl) } }
            }
        }.start()
    }

    private fun isTargetUrl(url: String): Boolean =
        HANIME_URL.any { url.startsWith(it) }

    private fun doPostLogin(absoluteUrl: String, body: String) {
        val cm = CookieManager.getInstance()
        val cookie = runCatching { cm.getCookie(absoluteUrl) }.getOrNull().orEmpty()

        // 照抄浏览器的请求头（参考 co3 的 HAR 1:1 对齐）
        val builder = Request.Builder().url(absoluteUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", absoluteUrl.substringBefore("/", "https:").let {
                // 取 scheme+host，如 https://hanime1.me
                Regex("^(https?://[^/]+)").find(absoluteUrl)?.groupValues?.get(1) ?: it
            })
            .header("Referer", absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)

        val reqBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val resp = EchHttp.loginClient.newCall(builder.post(reqBody).build()).execute()

        var code = -1
        var location: String? = null
        var hasLoginCookie = false
        resp.use {
            code = it.code
            Log.i(TAG, "postLogin <- ${it.code} $absoluteUrl")
            it.headers("Set-Cookie").forEach { raw ->
                var fixed = raw
                fixed = fixed.replace(Regex("(?i);\\s*Domain=[^;]+"), "")
                fixed = fixed.replace(Regex("(?i);\\s*Secure(?=;|$)"), "")
                fixed = fixed.replace(Regex("(?i);\\s*SameSite=[^;]+"), "; SameSite=Lax")
                runCatching { cm.setCookie(absoluteUrl, fixed) }
                // hanime1 的登录 cookie 名未知，按"非 session 的新 cookie"宽松判定；
                // 具体成功判定交给 onPageFinished 的 URL+cookie 检查
                if (!raw.contains("XSRF", ignoreCase = true)) hasLoginCookie = true
            }
            runCatching { cm.flush() }
            location = it.header("Location")?.trim()?.ifEmpty { null }
        }

        // 双保险：从 CookieManager 复核
        val finalCookie = runCatching { cm.getCookie(absoluteUrl) }.getOrNull().orEmpty()
        Log.i(TAG, "postLogin done: location=$location cookieLen=${finalCookie.length}")

        webView.post {
            try {
                val target = when {
                    location != null && location!!.startsWith("http") -> location!!
                    location != null -> {
                        val base = Regex("^(https?://[^/]+)").find(absoluteUrl)?.groupValues?.get(1)
                            ?: "https://hanime1.me"
                        base + location!!
                    }
                    else -> absoluteUrl
                }
                // 登录成功判定：服务器给了跳离登录页的 Location，且 CookieManager 里有会话。
                // 注意：登录失败（密码错）时服务器也会 302 回 /login 并刷新匿名 session，
                // 所以不能单靠 hasLoginCookie 判定，必须以 Location 是否离开登录页为准。
                val loginOk = location != null &&
                    !location!!.contains("/login", ignoreCase = true) &&
                    finalCookie.isNotEmpty()
                if (loginOk && callback != null) {
                    // 成功：直接回调，不 loadUrl，省掉等 ECH 加载跳转页的几秒
                    Log.i(TAG, "login success -> callback (skip loadUrl): location=$location")
                    callback.onLoginSuccess(finalCookie)
                } else {
                    // 失败或无回调：保留诊断 Toast + 加载目标页
                    // 不打印 body 内容（可能含密码），只打印是否携带 _token
                    val msg = "POST $code → ${location ?: "(无跳转)"} " +
                        "cookie:${finalCookie.length} loginCk:$hasLoginCookie " +
                        "token:${body.contains("_token")}"
                    Toast.makeText(webView.context, msg, Toast.LENGTH_LONG).show()
                    // 重新 loadUrl 真实页面 → 子请求由 HyWebViewHelper 走 ECH，CookieManager 会话生效
                    webView.loadUrl(target)
                }
            } catch (e: Exception) {
                Log.e(TAG, "postLogin loadUrl failed: ${e.message}")
            }
        }
    }
}
