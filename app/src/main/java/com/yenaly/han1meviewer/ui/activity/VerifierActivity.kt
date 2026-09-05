package com.yenaly.han1meviewer.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.HCookieJar
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import android.util.Base64
import java.security.MessageDigest

class VerifierActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emailInput = EditText(this).apply {
            hint = "电邮"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passwordInput = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tv = TextView(this).apply { textSize = 11f; setPadding(20,20,20,20); setTextIsSelectable(true) }
        val btn = Button(this).apply {
            text = "测试 javchu 登录 (GET+POST 全日志)"
            setOnClickListener { runTest(emailInput.text.toString(), passwordInput.text.toString()) }
        }
        val btnShare = Button(this).apply { text = "导出日志 (分享)"; setOnClickListener { shareLogs() } }
        val btnCopy = Button(this).apply { text = "复制到剪贴板"; setOnClickListener { copyLogs() } }
        val sv = ScrollView(this).apply { addView(tv) }
        val root = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(emailInput)
            addView(passwordInput)
            addView(btn)
            val row = android.widget.LinearLayout(this@VerifierActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(btnShare, android.widget.LinearLayout.LayoutParams(0,-2,1f))
                addView(btnCopy, android.widget.LinearLayout.LayoutParams(0,-2,1f))
            }
            addView(row)
            addView(sv, android.widget.LinearLayout.LayoutParams(-1,0,1f))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        tv.text = "点按钮开始\nBoringSSL+curl 全日志\n长按日志可复制，导出存 /sdcard/Download/verifier.log\n"
    }
    private fun log(s:String){ tv.append(s+"\n"); appendToFile(s) }
    private fun appendToFile(s:String){
        try{ java.io.File(getExternalFilesDir(null), "verifier.log").appendText(s+"\n") }catch(_:Exception){}
        try{ java.io.File("/sdcard/Download/verifier.log").appendText(s+"\n") }catch(_:Exception){}
    }
    private fun shareLogs(){
        val file = java.io.File(cacheDir, "javchu-verifier-${System.currentTimeMillis()}.txt")
        file.writeText(tv.text.toString())
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "分享验证器日志文件"))
    }
    private fun copyLogs(){
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("verifier", tv.text))
        android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun extractCookies(json: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val headers = json.optJSONArray("headers") ?: return result
        for (i in 0 until headers.length()) {
            val raw = headers.optString(i) ?: continue
            val value = if (raw.contains('\t')) raw.substringAfter('\t') else continue
            if (!raw.substringBefore('\t').equals("set-cookie", true)) continue
            val pair = value.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            if (name.isNotEmpty() && pair.contains('=')) result[name] = pair.substringAfter('=')
        }
        return result
    }

    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun redactCookieHeader(value: String): String =
        value.split(';').joinToString("; ") { part ->
            val name = part.substringBefore('=').trim()
            if (part.contains('=')) "$name=[REDACTED]" else part.trim()
        }

    private fun responseFeatures(json: JSONObject, body: String): String {
        val lower = body.lowercase()
        val headers = json.optJSONArray("headers")
        val locations = mutableListOf<String>()
        if (headers != null) for (i in 0 until headers.length()) {
            val raw = headers.optString(i)
            if (raw.substringBefore('\t').equals("location", true)) locations += "present"
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "features bodyLen=${body.length} sha256=$hash " +
            "authFailed=${lower.contains("auth.failed")} " +
            "loginForm=${lower.contains("name=\"password\"") || lower.contains("name='password'")} " +
            "userNode=${lower.contains("user-modal-name")} location=${locations.isNotEmpty()}"
    }

    private fun runTest(email: String, password: String){
        if (email.isBlank() || password.isBlank()) {
            android.widget.Toast.makeText(this, "请填写电邮和密码", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        log("=== javchu verifier BoringSSL+curl 全日志 ===")
        log("EchHttpClient.isLoaded=${EchHttpClient.isLoaded}")
        scope.launch(Dispatchers.IO){
            val dohUrl = DohConfig.resolveUrl() ?: "https://82sew1c85i.cloudflare-gateway.com/dns-query"
            val dohHost = try { dohUrl.toHttpUrl().host } catch (_: Exception) { "82sew1c85i.cloudflare-gateway.com" }
            val ips = DohConfig.bootstrapIps().ifEmpty { listOf("162.159.36.20","162.159.36.5") }
            val dohResolve = "$dohHost:443:${ips.joinToString(",")}"
            suspend fun doCurl(method:String, url:String, headers:List<String>, body:ByteArray?){
                try{
                    withContext(Dispatchers.Main){ log(">> $method $url headers=${headers.take(2)} bodyLen=${body?.size ?: 0}") }
                    val jsonStr = EchHttpClient.request(method, url, headers.toTypedArray(), body, dohUrl, dohResolve)
                    val json = JSONObject(jsonStr)
                    val code = json.optInt("statusCode", 0)
                    val echStatus = json.optString("echStatus", "")
                    val bodyB64 = json.optString("body","")
                    val bodyBytes = if(bodyB64.isNotEmpty()) Base64.decode(bodyB64, Base64.DEFAULT) else ByteArray(0)
                    val preview = String(bodyBytes).take(600).replace("\n"," ")
                    val hdrs = json.optJSONArray("headers")
                    var setCookie = ""
                    if(hdrs!=null) for(i in 0 until hdrs.length()){
                        val h = hdrs.optString(i) ?: continue
                        if(h.startsWith("set-cookie\t", true) || h.startsWith("Set-Cookie\t")) setCookie += h.substringAfter("\t").take(120)+"; "
                    }
                    val echLogs = json.optJSONArray("echLogs")?.let{ arr ->
                        (0 until arr.length()).joinToString("\n"){ arr.optString(it) }
                    } ?: ""
                    withContext(Dispatchers.Main){
                        log("<< $code echStatus=$echStatus setCookie=${setCookie.take(200)}")
                        if(echLogs.isNotBlank()) log("echLogs: $echLogs")
                        log("bodyPreview: $preview")
                    }
                }catch(e:Exception){
                    withContext(Dispatchers.Main){ log("FAIL $method $url ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            // GET 与 POST 必须复用同一份会话 Cookie
            var cookies = linkedMapOf<String, String>()
            var token = ""
            try {
                val getHeaders = listOf("User-Agent: Mozilla/5.0")
                withContext(Dispatchers.Main) { log(">> GET https://javchu.com/login headers=$getHeaders bodyLen=0") }
                val getJson = JSONObject(EchHttpClient.request("GET", "https://javchu.com/login", getHeaders.toTypedArray(), null, dohUrl, dohResolve))
                cookies.putAll(extractCookies(getJson))
                val bodyB64 = getJson.optString("body", "")
                val html = if (bodyB64.isNotEmpty()) String(Base64.decode(bodyB64, Base64.DEFAULT)) else ""
                token = Regex("name=\\\"_token\\\" value=\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.get(1) ?: ""
                withContext(Dispatchers.Main) {
                    log("<< ${getJson.optInt("statusCode", 0)} echStatus=${getJson.optString("echStatus", "")}")
                    log("tokenLen=${token.length} cookies=${cookies.keys.joinToString(",")}")
                    log("bodyPreview: ${html.take(600).replace("\\n", " ")}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("GET/解析失败 ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (token.isBlank() || cookies.isEmpty()) {
                withContext(Dispatchers.Main) { log("中止 POST：token 或 Cookie 为空") }
            } else {
                val cookie = cookieHeader(cookies)
                val form = "_token=${java.net.URLEncoder.encode(token, "UTF-8")}&email=${java.net.URLEncoder.encode(email, "UTF-8")}&password=${java.net.URLEncoder.encode(password, "UTF-8")}"
                val postHeaders = listOf(
                    "User-Agent: Mozilla/5.0",
                    "Content-Type: application/x-www-form-urlencoded",
                    "Cookie: $cookie",
                    "Referer: https://javchu.com/login",
                    "Origin: https://javchu.com",
                )
                withContext(Dispatchers.Main) { log(">> POST https://javchu.com/login headers=Cookie: ${redactCookieHeader(cookie)} bodyLen=${form.toByteArray().size}") }
                try {
                    val postJson = JSONObject(EchHttpClient.request("POST", "https://javchu.com/login", postHeaders.toTypedArray(), form.toByteArray(), dohUrl, dohResolve))
                    withContext(Dispatchers.Main) {
                        log("<< ${postJson.optInt("statusCode", 0)} echStatus=${postJson.optString("echStatus", "")}")
                        log("POST响应 Set-Cookie=${extractCookies(postJson).keys.joinToString(",")}")
                        val postB64 = postJson.optString("body", "")
                        val postBody = if (postB64.isNotEmpty()) String(Base64.decode(postB64, Base64.DEFAULT)) else ""
                        log("${responseFeatures(postJson, postBody)}")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { log("POST失败 ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            withContext(Dispatchers.Main){ log("=== 完成，请分享此 TXT 日志对比 HAR（密码和 Cookie 已脱敏）===") }
        }
    }
}
