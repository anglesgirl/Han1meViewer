package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebView 登录的表单劫持（移植 CO3 的 `EchWebViewManager.Bridge`）。
 *
 * **为什么必须劫持**：`shouldInterceptRequest` 拿不到 POST 的 body，
 * 所以登录 POST 无法在那里接管 —— 若放任 WebView 自己发，那一次请求会用
 * WebView 自己的 TLS 栈、**明文暴露被墙域名的 SNI**，fail-closed 就形同虚设。
 *
 * 做法：注入 JS 拦住表单的 submit → 把 action + 序列化后的 body 交给原生 →
 * 原生用 OkHttp（Conscrypt + ECH）代发，把响应里的 Set-Cookie 写进 CookieManager
 * → 成功后重新 loadUrl 真实地址（**不能**用 loadDataWithBaseURL 渲染静态 HTML：
 * 那样 JS 不执行、cookie 不同步，页面上的登录态不会更新）。
 *
 * 【关键细节，别简化】登录 POST 必须用 **followRedirects(false)** 的客户端：
 * 302 响应上挂着的 `Set-Cookie`（登录凭据就在里面）一旦被自动跟随就被吃掉了，
 * 只看得到最终的 200 —— 这正是老 JNI 链路"登录一直失败却查不出原因"的机制。
 *
 * @param onSuccess 登录成功回调，参数是从 CookieManager 汇总出的 cookie 串
 * @param onFailure 登录失败回调（附原因文案，用于就地提示）
 * @param loginUrlOf 由调用方给出"当前站点的登录页地址"，用于失败后回退
 */
class EchLoginBridge(
    private val webView: WebView,
    private val loginUrlOf: () -> String,
    private val onSuccess: (List<String>) -> Unit,
    private val onFailure: (String) -> Unit,
) {

    companion object {
        private const val TAG = "HY-ECH-LOGIN"
        const val JS_NAME = "HyEchBridge"

        /** 完整浏览器头：缺 Referer/Origin 时不少站点会当无效请求 */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/137.0.0.0 Mobile Safari/537.36"

        fun install(
            webView: WebView,
            loginUrlOf: () -> String,
            onSuccess: (List<String>) -> Unit,
            onFailure: (String) -> Unit,
        ): EchLoginBridge {
            val bridge = EchLoginBridge(webView, loginUrlOf, onSuccess, onFailure)
            webView.addJavascriptInterface(bridge, JS_NAME)
            return bridge
        }
    }

    /**
     * 注入表单劫持脚本。
     *
     * ⚠️ 选择器刻意**不依赖具体 id**（各站点登录表单 id 不同，写死会在换站时就失效）：
     * 只拦"含有密码输入框"的表单 —— 这样搜索框、筛选表单都不会被误拦。
     * 同时用 MutationObserver 兜住动态插入的表单（页面加载完才出现的情况）。
     */
    fun inject() {
        val js = """
            (function(){
              function scan(){
                var forms = document.forms || [];
                for (var i=0;i<forms.length;i++){
                  var f = forms[i];
                  if (f._hyHijacked) continue;
                  // 只拦"含密码框"的表单：登录/注册；搜索框之类不碰
                  var pw = f.querySelector ? f.querySelector('input[type=password]') : null;
                  if (!pw) continue;
                  f._hyHijacked = true;
                  try { window.$JS_NAME.onHijacked('bound:' + (f.id||f.name||i)); } catch(e){}
                  f.addEventListener('submit', function(e){
                    e.preventDefault();
                    e.stopPropagation();
                    try {
                      var fd = new FormData(f);
                      var params = new URLSearchParams();
                      for (var pair of fd.entries()) { params.append(pair[0], pair[1]); }
                      window.$JS_NAME.postLogin(f.action || window.location.href, params.toString());
                    } catch(err) {
                      try { window.$JS_NAME.onHijacked('err:' + err); } catch(e){}
                      f.submit();   // 劫持失败就退回原生提交，别把用户卡死
                    }
                  }, true);
                }
              }
              scan();
              try { new MutationObserver(scan).observe(document, {childList:true, subtree:true}); } catch(e){}
            })();
        """.trimIndent()
        webView.post { runCatching { webView.evaluateJavascript(js, null) } }
    }

    @JavascriptInterface
    fun onHijacked(msg: String) {
        Log.i(TAG, "hijack: ${msg.take(120)}")
    }

    @JavascriptInterface
    fun postLogin(url: String, body: String) {
        val base = runCatching { java.net.URI(loginUrlOf()).let { "${it.scheme}://${it.host}" } }
            .getOrElse { "https://hanime1.me" }
        val absoluteUrl = if (url.startsWith("http")) url else base + url
        Log.i(TAG, "postLogin $absoluteUrl bodyLen=${body.length}")

        Thread {
            try {
                val cm = CookieManager.getInstance()
                // ⚠️ followRedirects(false)：否则读不到 302 上的 Set-Cookie
                val loginClient = EchHttp.loginClient
                val req = Request.Builder()
                    .url(absoluteUrl)
                    .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Origin", base)
                    .header("Referer", loginUrlOf())
                    .header("User-Agent", UA)
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .build()

                var status = 200
                var location: String? = null
                var html = ""
                val cookieChanged = mutableListOf<String>()
                loginClient.newCall(req).execute().use { resp ->
                    status = resp.code
                    location = resp.header("Location")
                    html = resp.body?.string() ?: ""
                    for (raw in resp.headers.values("Set-Cookie")) {
                        // 去掉 Domain/Secure 限制，确保 WebView 那边也能收下
                        var fixed = raw.replace(Regex(";\\s*Domain=[^;]+", RegexOption.IGNORE_CASE), "")
                        fixed = fixed.replace(Regex(";\\s*Secure", RegexOption.IGNORE_CASE), "")
                        fixed = fixed.replace(Regex(";\\s*SameSite=[^;]+", RegexOption.IGNORE_CASE), "; SameSite=Lax")
                        runCatching { cm.setCookie(absoluteUrl, fixed) }
                        runCatching { cm.setCookie(base + "/", fixed) }
                        cookieChanged += fixed
                    }
                    runCatching { cm.flush() }
                }

                // 从 CookieManager 汇总当前 cookie（与 WebView 同源），交给上层的 login()
                val cookies = LinkedHashSet<String>()
                runCatching { cm.getCookie(absoluteUrl) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { cookies += it }
                runCatching { cm.getCookie(base + "/") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { cookies += it }

                // 成功判定：拿到重定向（离开登录页）= 服务端认可；或本次响应确实写入了新 cookie
                val redirectedAway = status in 300..399 &&
                    location != null && !location!!.contains("login")
                val success = redirectedAway || (status == 200 && cookieChanged.isNotEmpty() && cookies.isNotEmpty())

                // 密码错之类的明确失败页特征
                val wrongPwd = !success && status == 200 && Regex(
                    "wrong|incorrect|invalid|密码|錯誤|错误",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(html)

                Log.i(TAG, "postLogin status=$status success=$success wrongPwd=$wrongPwd loc=${location?.take(60)}")

                webView.post {
                    when {
                        wrongPwd -> {
                            runCatching { webView.evaluateJavascript("alert('登录失败，请检查账号密码');", null) }
                            runCatching { webView.loadUrl(loginUrlOf()) }
                            onFailure("账号或密码不正确")
                        }
                        success -> {
                            runCatching { cm.flush() }
                            onSuccess(cookies.toList())
                        }
                        status in 300..399 && location != null -> {
                            // 其它重定向：按语义跟随（用真实的绝对地址重新加载，走 ECH 拦截）
                            val target = if (location!!.startsWith("http")) location!! else base + location
                            runCatching { webView.loadUrl(target) }
                            if (cookies.isNotEmpty()) onSuccess(cookies.toList())
                            else onFailure("登录未完成，请重试")
                        }
                        html.isNotEmpty() -> {
                            // 非重定向的 200：多半是带回错误的登录页，交回 WebView 渲染
                            runCatching {
                                webView.loadDataWithBaseURL(absoluteUrl, html, "text/html", "utf-8", absoluteUrl)
                            }
                            onFailure(if (wrongPwd) "账号或密码不正确" else "登录未完成，请重试")
                        }
                        else -> {
                            runCatching { webView.loadUrl(loginUrlOf()) }
                            onFailure("登录未完成，请重试")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "postLogin failed: ${t.message}")
                webView.post {
                    runCatching { webView.loadUrl(loginUrlOf()) }
                    onFailure(t.message ?: "网络异常")
                }
            }
        }.start()
    }
}
