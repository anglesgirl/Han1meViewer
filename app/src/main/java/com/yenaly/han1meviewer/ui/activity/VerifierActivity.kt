package com.yenaly.han1meviewer.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yenaly.han1meviewer.logic.network.HCookieJar
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

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
        tv.text = "点按钮开始\nGo ECH 代理端口=${io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager.port}\n长按日志可复制，导出的文件在 /sdcard/Download/verifier.log 也会自动保存\n"
    }
    private fun log(s:String){ tv.append(s+"\n"); appendToFile(s) }
    private fun appendToFile(s:String){
        try{
            val f = java.io.File(getExternalFilesDir(null), "verifier.log")
            f.appendText(s+"\n")
        }catch(_:Exception){}
        try{
            val f2 = java.io.File("/sdcard/Download/verifier.log")
            f2.appendText(s+"\n")
        }catch(_:Exception){}
    }
    private fun shareLogs(){
        val text = tv.text.toString()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
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
        log("=== javchu verifier Go-ECH 全日志 ===")
        scope.launch(Dispatchers.IO){
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(io.github.daisukikaffuchino.han1meviewer.logic.network.EchInterceptor())
                .addInterceptor(Interceptor { chain ->
                    val req2 = chain.request().newBuilder().removeHeader("Expect").build()
                    // log sync (cannot suspend)
                    android.util.Log.d("Verifier","REQ ${req2.method} ${req2.url}")
                    val resp = chain.proceed(req2)
                    android.util.Log.d("Verifier","RESP ${resp.code}")
                    resp
                })
                .build()
            suspend fun doReq(method:String, url:String, body: RequestBody?){
                try{
                    val req = Request.Builder().url(url).method(method, body).header("User-Agent","Mozilla/5.0").build()
                    val resp = client.newCall(req).execute()
                    val b = resp.body?.string()?.take(600)?.replace("\n"," ") ?: ""
                    withContext(Dispatchers.Main){ log("$method $url -> ${resp.code} bodyLen=${b.length} preview=${b.take(500)}") }
                }catch(e:Exception){
                    withContext(Dispatchers.Main){ log("FAIL $method $url ${e.message}") }
                }
            }
            doReq("GET","https://javchu.com/login", null)
            // 取 _token
            var token=""
            try{
                val r = client.newCall(Request.Builder().url("https://javchu.com/login").build()).execute()
                val html = r.body?.string() ?: ""
                token = Regex("name=\"_token\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
                withContext(Dispatchers.Main){ log("token=${token.take(20)}...") }
            }catch(_:Exception){}
            val form = FormBody.Builder().add("_token", token).add("email","test@example.com").add("password","123456").build()
            doReq("POST","https://javchu.com/login", form)
            withContext(Dispatchers.Main){ log("=== 完成，对比 HAR 看 Cookie/_token/Expect ===")}
        }
    }
    private suspend fun logOnMain(s:String)=withContext(Dispatchers.Main){ log(s) }
}
