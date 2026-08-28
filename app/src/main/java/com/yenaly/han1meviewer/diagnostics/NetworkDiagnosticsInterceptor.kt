package com.yenaly.han1meviewer.diagnostics

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.system.measureTimeMillis

/** Records host-level request outcomes without exposing paths, headers, or bodies. */
class NetworkDiagnosticsInterceptor(
    private val client: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        var response: Response? = null
        val elapsedMs = measureTimeMillis {
            try {
                response = chain.proceed(request)
            } catch (error: IOException) {
                Diagnostics.event(
                    "network_failure",
                    mapOf(
                        "client" to client,
                        "host" to host,
                        "method" to request.method,
                        "error_type" to error.javaClass.simpleName,
                        "error" to (error.message ?: "unknown"),
                    ),
                )
                throw error
            }
        }
        Diagnostics.event(
            "network_response",
            mapOf(
                "client" to client,
                "host" to host,
                "method" to request.method,
                "status" to response!!.code,
                "elapsed_ms" to elapsedMs,
            ),
        )
        return response!!
    }
}
