package com.yenaly.han1meviewer.logic.network.ech

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 419 诊断日志上报：把 MiniProxy 的关键诊断事件 POST 到 log 服务器，
 * 用户无需抓包即可远程查看。
 *
 * 上报地址：https://log.anglesgirl.eu.org/v1/events?app=han1meviewer
 * Body: {"event": ..., "ts": ISO8601, ...fields}
 *
 * - 用独立 OkHttpClient 直连（不走 ECH，log 服务器不需要 ECH）
 * - Dispatchers.IO 异步执行，失败静默（Log.w，不抛异常）
 * - 敏感值（cookie 值、token、密码）由调用方打码后传入，只上报名称和存在性
 */
object EchLogReporter {

    private const val TAG = "EchLogReporter"
    private const val ENDPOINT = "https://log.anglesgirl.eu.org/v1/events?app=han1meviewer"

    /** 上报开关：默认关闭，诊断时由用户手动开启 */
    @Volatile
    var enabled: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    /**
     * 上报一个诊断事件。fields 中的值只支持 String/Number/Boolean/null，
     * 调用方负责把敏感值打码。
     */
    fun report(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        scope.launch {
            try {
                val body = buildJson(event, fields)
                val req = Request.Builder()
                    .url(ENDPOINT)
                    .post(body.toRequestBody(jsonMediaType))
                    .header("Content-Type", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "report $event failed: http ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "report $event failed: ${e.message}")
            }
        }
    }

    private fun buildJson(event: String, fields: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"event\":").append(jsonString(event)).append(',')
        sb.append("\"ts\":").append(jsonString(Instant.now().toString()))
        for ((k, v) in fields) {
            sb.append(',')
            sb.append(jsonString(k)).append(':').append(jsonValue(v))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        else -> jsonString(v.toString())
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
