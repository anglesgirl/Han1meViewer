package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class VerifierActivity : AppCompatActivity() {
    private lateinit var tv: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply { textSize = 11f; setPadding(20,20,20,20) }
        val btn = Button(this).apply { text = "测试 javchu 登录 (GET+POST 全日志)"; setOnClickListener { runTest() } }
        val sv = ScrollView(this).apply { addView(tv) }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btn)
            addView(sv, android.widget.LinearLayout.LayoutParams(-1,0,1f))
        }
        setContentView(root)
        tv.text = "点按钮开始\nGo ECH 代理端口=${io.github.daisukikaffuchino.han1meviewer.logic.ech.EchProxyManager.port}\n"
    }
    private fun log(s:String){ tv.append(s+"\n") }
    private fun runTest(){
        tv.text=""
        log("=== javchu verifier Go-ECH 全日志 ===")
        scope.launch(Dispatchers.IO){
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(io.github.daisukikaffuchino.han1meviewer.logic.network.EchInterceptor())
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request().newBuilder().removeHeader("Expect").build()
                    logOnMain("REQ ${req.method} ${req.url} headers=${req.headers.toString().take(400)}")
                    val resp = chain.proceed(req)
                    logOnMain("RESP ${resp.code} ${resp.message} set-cookie=${resp.headers("Set-Cookie").take(1).toString().take(200)}")
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
