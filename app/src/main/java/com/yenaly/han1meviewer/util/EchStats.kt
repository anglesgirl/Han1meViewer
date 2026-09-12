package com.yenaly.han1meviewer.util

import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 自有埋点:直连自建日志服务,不经 ECH 代理(服务本身可直连),
 * 失败静默,不影响业务。开关复用设置里的使用情况统计。
 *
 * 只上报行为事件与版本号,绝不上报账号、密码、Cookie、Token。
 */
object EchStats {

    private const val ENDPOINT = "https://log.anglesgirl.eu.org/v1/events"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun event(name: String, fields: Map<String, String> = emptyMap()) {
        if (!Preferences.isAnalyticsEnabled) return
        scope.launch {
            runCatching {
                val payload = JSONObject()
                    .put("app", "han1meviewer")
                    .put("event", name)
                    .put("timestamp", Instant.now().toString())
                    .put(
                        "version",
                        "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                    )
                    .put("fields", JSONObject(fields))
                val req = Request.Builder()
                    .url(ENDPOINT)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }
}
