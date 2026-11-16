package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.HA1_GITHUB_API_URL
import com.yenaly.han1meviewer.HJson
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.interceptor.CloudflareInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.GetchuInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.SpeedLimitInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import com.yenaly.han1meviewer.logic.network.ech.echTransport
import com.yenaly.han1meviewer.util.applicationContext
import com.yenaly.han1meviewer.util.unsafeLazy
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

internal fun isGitHubApiUrl(host: String, isHttps: Boolean): Boolean =
    isHttps && host.equals("api.github.com", ignoreCase = true)

internal fun Request.withGitHubApiToken(token: String): Request {
    if (token.isBlank() || !isGitHubApiUrl(url.host, url.isHttps)) return this
    return newBuilder().header("Authorization", "Bearer $token").build()
}

internal fun retryGitHubApiAnonymously(response: Response): Request? {
    val request = response.request
    val authorization = request.header("Authorization")
    if (response.code != 401 ||
        !isGitHubApiUrl(request.url.host, request.url.isHttps) ||
        authorization.isNullOrBlank() ||
        !authorization.startsWith("Bearer ", ignoreCase = true)
    ) {
        return null
    }
    return request.newBuilder().removeHeader("Authorization").build()
}

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:35
 */
object ServiceCreator {

    private val cache = Cache(
        directory = File(applicationContext.cacheDir, "http_cache"),
        maxSize = 10 * 1024 * 1024
    )

    private val downloadSpeedLimitInterceptor by unsafeLazy {
        SpeedLimitInterceptor(maxSpeed = Preferences.downloadSpeedLimit)
    }

    private val dns = HDns()

    inline fun <reified T> create(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(hClient)
        .build()
        .create(T::class.java)

    inline fun <reified T> createGitHubApi(): T = Retrofit.Builder()
        .baseUrl(HA1_GITHUB_API_URL)
        .client(githubClient)
        .addConverterFactory(HJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    inline fun <reified T> createGetchu(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(getchuClient)
        .build()
        .create(T::class.java)

    /**
     * OkHttpClient
     */
    var hClient: OkHttpClient = buildHClient()
        private set

    var githubClient: OkHttpClient = buildGithubClient()
        private set

    var downloadClient: OkHttpClient = buildDownloadClient()
        private set

    var getchuClient: OkHttpClient = buildGetchuClient()
        private set

    /**
     * Rebuild OkHttpClient
     */
    fun rebuildOkHttpClient() {
        hClient = buildHClient()
        getchuClient = buildGetchuClient()
    }

    private fun buildGetchuClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(GetchuInterceptor())
            .cookieJar(CookieJar.NO_COOKIES)
            .proxySelector(HProxySelector())
            .echTransport(dns)
            .build()
    }

    private fun buildDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(downloadSpeedLimitInterceptor)
            .echTransport(dns)
            .build()
    }

    /**
     * Build OkHttpClient
     */
    private fun buildHClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(CloudflareInterceptor(applicationContext))
            .cache(cache)
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .echTransport(dns)
            .build()
    }

    private fun buildGithubClient(): OkHttpClient {
        // 令牌可能为空（CI/本地构建时没有配置 HA_GITHUB_TOKEN）。以前这里无条件带上
        // "Bearer "，会得到一个必然 401 的非法凭证，反而掩盖了真正的原因；
        // 空令牌时干脆走匿名请求（限额更低，但至少不会自己把自己弄挂）。
        val token = BuildConfig.HA_GITHUB_TOKEN.trim()
        if (token.isEmpty()) {
            Log.w("ServiceCreator", "HA_GITHUB_TOKEN 为空，GitHub 请求将以匿名方式进行（限额更低）")
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request()
                // Release APK 下载会改写到 gh.anglesya.win；令牌仅发给 GitHub 官方 API。
                return@addInterceptor chain.proceed(request.withGitHubApiToken(token))
            }
            .authenticator { _, response ->
                val retryRequest = retryGitHubApiAnonymously(response)
                if (retryRequest != null) {
                    Log.w("ServiceCreator", "GitHub API rejected the configured token; retrying anonymously")
                }
                retryRequest
            }
            .build()
    }
}
