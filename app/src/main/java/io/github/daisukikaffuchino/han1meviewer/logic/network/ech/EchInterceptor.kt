package io.github.daisukikaffuchino.han1meviewer.logic.network.ech

import android.util.Log
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.ProtocolException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * OkHttp application interceptor that routes hanime1.me requests
 * through the Go ECH proxy, encrypting the TLS SNI.
 *
 * Replaces the former GeckoInterceptor. Instead of GeckoWebExecutor,
 * it delegates to a local Go binary that performs the ECH TLS handshake
 * using Go 1.23's crypto/tls.
 *
 * - Handles cookies (load/save) for the original domain.
 * - Follows redirects through the proxy (up to MAX_REDIRECTS).
 * - Falls back to direct OkHttp if the proxy is unavailable.
 */
class EchInterceptor(
    private val cookieJar: CookieJar? = null,
) : Interceptor {

    companion object {
        private const val TAG = "EchInterceptor"
        private const val MAX_REDIRECTS = 5

        // Use the app's domain list dynamically — any domain in HanimeConstants
        // will be routed through the ECH proxy. The Go proxy tries ECH for all
        // domains; if ECH config is unavailable, it falls back to plain TLS.
        val echHosts = HanimeConstants.HANIME_HOSTNAME.toList()
    }

    /**
     * Lightweight OkHttp client for talking to the local proxy.
     * No interceptors, no cookie jar, no redirects — we handle those ourselves.
     * Uses NO_PROXY to avoid routing localhost requests through the Go proxy itself.
     */
    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (!shouldUseEch(host)) {
            return chain.proceed(request)
        }

        // The proxy auto-starts in HanimeApplication.onCreate().
        // If it's not ready yet (still starting or failed), fall back to direct.
        if (!GoProxyManager.isRunning()) {
            return chain.proceed(request)
        }

        return try {
            executeThroughProxy(request)
        } catch (e: Exception) {
            Log.e(TAG, "Proxy request failed, falling back: ${request.url}", e)
            chain.proceed(request)
        }
    }

    private fun executeThroughProxy(request: Request): Response {
        var currentRequest = request
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            // Load cookies for the original domain
            val cookieHeader = if (cookieJar != null) {
                cookieJar.loadForRequest(currentRequest.url)
                    .joinToString("; ") { "${it.name}=${it.value}" }
            } else ""

            // Build proxy request
            val proxyUrl = GoProxyManager.proxyUrl(currentRequest.url.toString())
            val proxyBuilder = currentRequest.newBuilder()
                .url(proxyUrl)
                .header("User-Agent", USER_AGENT)

            if (cookieHeader.isNotEmpty()) {
                proxyBuilder.header("Cookie", cookieHeader)
            }

            val proxyRequest = proxyBuilder.build()
            val response = proxyClient.newCall(proxyRequest).execute()

            // Save cookies from response
            if (cookieJar != null) {
                val cookies = response.headers("Set-Cookie")
                    .mapNotNull { Cookie.parse(currentRequest.url, it) }
                if (cookies.isNotEmpty()) {
                    cookieJar.saveFromResponse(currentRequest.url, cookies)
                }
            }

            // Handle redirects
            if (response.code in 301..308) {
                val location = response.header("Location")
                response.close()
                if (location != null) {
                    val redirectUrl = currentRequest.url.resolve(location)
                    if (redirectUrl != null) {
                        redirectCount++
                        Log.i(TAG, "Redirect ($redirectCount/$MAX_REDIRECTS): $location")
                        currentRequest = currentRequest.newBuilder()
                            .url(redirectUrl)
                            .build()
                        continue
                    }
                }
                throw ProtocolException("Bad redirect")
            }

            // Return response with original request URL
            return response.newBuilder()
                .request(currentRequest)
                .build()
        }

        throw ProtocolException("Too many redirects ($MAX_REDIRECTS)")
    }

    private fun shouldUseEch(host: String): Boolean {
        return echHosts.any { host == it || host.endsWith(".$it") }
    }
}
