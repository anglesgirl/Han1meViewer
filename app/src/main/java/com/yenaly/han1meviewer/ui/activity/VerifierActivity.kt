package com.yenaly.han1meviewer.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.HCookieJar
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import android.util.Base64

class VerifierActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply { textSize = 11f; setPadding(20,20,20,20); isTextSelectable = true }
        val btn = Button(this).apply { text = "测试 javchu 登录 (GET+POST 全日志)"; setOnClickListener { runTest() } }
        val btnShare = Button(this).apply { text = "导出日志 (分享)"; setOnClickListener { shareLogs() } }
        val btnCopy = Button(this).apply { text = "复制到剪贴板"; setOnClickListener { copyLogs() } }
        val sv = ScrollView(this).apply { addView(tv) }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
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
        tv.text = "点按钮开始\nBoringSSL+curl 全日志\n长按日志可复制，导出存 /sdcard/Download/verifier.log\n"
    }
    private fun log(s:String){ tv.append(s+"\n"); appendToFile(s) }
    private fun appendToFile(s:String){
        try{ java.io.File(getExternalFilesDir(null), "verifier.log").appendText(s+"\n") }catch(_:Exception){}
        try{ java.io.File("/sdcard/Download/verifier.log").appendText(s+"\n") }catch(_:Exception){}
    }
    private fun shareLogs(){
        val text = tv.text.toString()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "分享验证器日志"))
    }
    private fun copyLogs(){
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("verifier", tv.text))
        android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun runTest(){
        tv.text=""
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
            // 1. GET login
            doCurl("GET","https://javchu.com/login", listOf("User-Agent: Mozilla/5.0"), null)
            // 取 token 再 POST (用 curl 取 html 再解析)
            var token=""
            try{
                val jsonStr = EchHttpClient.request("GET","https://javchu.com/login", arrayOf("User-Agent: Mozilla/5.0"), null, dohUrl, dohResolve)
                val bodyB64 = JSONObject(jsonStr).optString("body","")
                val html = if(bodyB64.isNotEmpty()) String(Base64.decode(bodyB64, Base64.DEFAULT)) else ""
                token = Regex("name=\"_token\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
                withContext(Dispatchers.Main){ log("token=${token.take(30)}... len=${token.length}") }
                // 打印 cookie Jar
                val cookies = HCookieJar().loadForRequest("https://javchu.com/login".toHttpUrl())
                withContext(Dispatchers.Main){ log("CookieJar for javchu: ${cookies.joinToString(";"){it.name+"="+it.value.take(20)}}") }
            }catch(e:Exception){
                withContext(Dispatchers.Main){ log("token parse fail ${e.message}") }
            }
            // 2. POST login (假账号，看是否 419 还是 302)
            val form = "_token=${java.net.URLEncoder.encode(token,"UTF-8")}&email=test@example.com&password=123456"
            doCurl("POST","https://javchu.com/login", listOf("User-Agent: Mozilla/5.0","Content-Type: application/x-www-form-urlencoded"), form.toByteArray())
            withContext(Dispatchers.Main){ log("=== 完成，请分享此日志对比 HAR (Cookie/_token/Expect) ===") }
        }
    }
}
