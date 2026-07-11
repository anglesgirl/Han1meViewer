package com.yenaly.han1meviewer.logic.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.HA1_GITHUB_API_URL
import com.yenaly.han1meviewer.HJson
import com.yenaly.han1meviewer.Preferences
import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.CloudflareInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.CronetCookieInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.GetchuInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.SpeedLimitInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import com.yenaly.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import com.yenaly.yenaly_libs.utils.applicationContext
import com.yenaly.yenaly_libs.utils.unsafeLazy
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

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
        downloadClient = buildDownloadClient()
        getchuClient = buildGetchuClient()
    }

    /**
     * 是否使用 Cronet 传输层（启用 ECH）。
     * cronet-embedded 同步初始化，CronetManager.init() 完成后即就绪。
     */
    private fun useCronet(): Boolean =
        Preferences.useECH && CronetManager.isReady && CronetManager.engine != null

    /**
     * 添加 Cronet 拦截器到 OkHttpClient.Builder。
     * CronetInterceptor 必须是最后一个应用拦截器。
     * CookieJar 被 Cronet 绕过，需通过 CronetCookieInterceptor 手动处理。
     */
    private fun OkHttpClient.Builder.addCronetIfNeeded(
        cookieJar: HCookieJar? = null,
    ): OkHttpClient.Builder {
        if (!useCronet()) return this
        cookieJar?.let { addInterceptor(CronetCookieInterceptor(it)) }
        CronetManager.engine?.let { engine ->
            addInterceptor(CronetInterceptor.newBuilder(engine).build())
        }
        return this
    }

    private fun buildGetchuClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(GetchuInterceptor())

        if (useCronet()) {
            builder.addCronetIfNeeded()
        } else {
            builder.cookieJar(CookieJar.NO_COOKIES)
                .proxySelector(HProxySelector())
                .dns(dns)
        }
        return builder.build()
    }

    private fun buildDownloadClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(downloadSpeedLimitInterceptor)

        if (useCronet()) {
            builder.addCronetIfNeeded()
        } else {
            builder.protocols(listOf(Protocol.HTTP_1_1))
                .dns(dns)
        }
        return builder.build()
    }

    /**
     * Build OkHttpClient
     */
    private fun buildHClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(CloudflareInterceptor(applicationContext))

        if (useCronet()) {
            builder.addCronetIfNeeded(HCookieJar())
        } else {
            builder.cache(cache)
                .cookieJar(HCookieJar())
                .proxySelector(HProxySelector())
                .dns(dns)
        }
        return builder.build()
    }

    private fun buildGithubClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().addHeader(
                    "Authorization", "Bearer ${BuildConfig.HA_GITHUB_TOKEN}"
                ).build()
                return@addInterceptor chain.proceed(request)
            }
            .build()
    }
}
