package com.yenaly.han1meviewer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.crashlytics.setCustomKeys
import com.google.firebase.database.database
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.yenaly.han1meviewer.logic.network.HDns
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.logic.network.ech.echTransport
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.han1meviewer.ui.activity.MainActivity
import com.yenaly.han1meviewer.util.AnimeShaders
import com.yenaly.han1meviewer.util.ThemeUtils
import com.developer.crashx.config.CrashConfig
import com.yenaly.yenaly_libs.base.YenalyApplication
import com.yenaly.yenaly_libs.utils.LanguageHelper
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import `is`.xyz.mpv.MPVLib
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : YenalyApplication(), SingletonImageLoader.Factory {

    companion object {
        const val TAG = "HanimeApplication"
    }

    /**
     * 图片链路专用 OkHttp —— 必须挂 ECH 传输层。
     *
     * Coil 默认自建 OkHttpClient，走的是**系统 TLS 栈**（`com.android.org.conscrypt`），
     * 而系统栈没有 ECH：在被 SNI 阻断的网络里，图片请求会在 TLS 握手阶段直接被 RST。
     * 用户报障日志正是如此：
     *   `CoilError: Image load failed` → `SocketException: Connection reset`
     *     at `com.android.org.conscrypt.ConscryptEngineSocket.doHandshake`
     * 同网络下 Chrome 打得开、App 打不开 —— 差别就在 Chrome 自己有 ECH，而图片这条链路没有。
     *
     * 受保护域名（[HanimeConstants.HANIME_HOSTNAME]）由 EchSocketFactory 注入 ECHConfigList、
     * 并由 EchDns 走 DoH 解析；其他域名（getchu / picsum 等）行为不变。
     */
    private val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .echTransport(HDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Coil 全局 ImageLoader：把图片请求接到 ECH 传输层（此前 Coil 用默认 client，完全没有 ECH）。 */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(imageClient)) }
        .build()

    /**
     * 已在 [initCrashX] 中透過 CrashX 處理
     */
    override val isDefaultCrashHandlerEnabled: Boolean = false

    private fun initCrashX() {
        CrashConfig.Builder.create()
            .backgroundMode(CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM)
            .enabled(true)
            .includeDeviceInfo(true)
            .showErrorDetails(true)
            .showRestartButton(true)
            .showCloseButton(true)
            .showReportButton(true)
            .showCopyButtonInDetails(true)
            .logErrorOnRestart(true)
            .trackActivities(true)
            .minTimeBetweenCrashesMs(3000)
            .errorTitle(getString(R.string.crash_title))
            .errorDrawable(R.drawable.h_chan_cry)
            .errorMessage(getString(R.string.crash_message))
            .restartButtonText(getString(R.string.crash_restart))
            .closeButtonText(getString(R.string.crash_close))
            .detailsButtonText(getString(R.string.crash_details))
            .reportButtonText(getString(R.string.crash_report))
            .copyButtonText(getString(R.string.crash_copy))
            .restartActivity(MainActivity::class.java)
            .apply()
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(android.app.ActivityManager::class.java)
        return am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == packageName
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        initCrashX()
        ThemeUtils.applyDarkModeFromPreferences(this)
        if (Preferences.useDynamicColor){
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        ProxySelector.setDefault(HProxySelector())
        HProxySelector.rebuildNetwork()
        // ECH 传输层：装载 Conscrypt provider（in-process，无外挂线程/无本地端口）。
        // 这是取代原先 Go 本地反代的唯一改动 —— WebView 的子请求由
        // shouldInterceptRequest 接管后走这条通道拿到 ECH。
        com.yenaly.han1meviewer.logic.network.ech.ConscryptEch.install()
        // H3（QUIC + ECH）传输层：注册 Context（负缓存落盘 ech_h3_state 要用），
        // native 库在首次真正用到时才 dlopen —— 缺库不影响其余功能。
        com.yenaly.han1meviewer.logic.network.ech.HyEchH3.attach(this)
        com.yenaly.han1meviewer.util.EchStats.event("app_start")
        initFirebase()
        initNotificationChannel()
        MPVLib.create(applicationContext)
        MPVLib.init()

        if (AnimeShaders.copyShaderAssets(applicationContext) <= 0) {
            Log.w(TAG, "Shader 复制失败")
        }
        if (AnimeShaders.copyCertAssets(applicationContext) <= 0) {
            Log.w(TAG, "cert 复制失败")
        }
        val selected = Preferences.fakeLauncherIcon
        switchLauncher(selected)
    }

    private fun initFirebase() {
        // 用于处理 Firebase Analytics 初始化
        Firebase.analytics.setAnalyticsCollectionEnabled(Preferences.isAnalyticsEnabled)
        // 用于处理 Firebase Crashlytics 初始化
        Firebase.crashlytics.apply {
            isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            setCustomKeys {
                key(
                    FirebaseConstants.APP_LANGUAGE,
                    LanguageHelper.preferredLanguage.toLanguageTag()
                )
                key(
                    FirebaseConstants.VERSION_SOURCE,
                    BuildConfig.VERSION_SOURCE
                )
            }
        }
        // 用于处理 Firebase Remote Config 初始化
        Firebase.remoteConfig.apply {
            setConfigSettingsAsync(remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3 * 60 * 60
                fetchTimeoutInSeconds = 10
            })
            setDefaultsAsync(FirebaseConstants.remoteConfigDefaults)
            fetchAndActivate().addOnCompleteListener {
                AppViewModel.getLatestVersion(delayMillis = 200)
            }
        }
        Firebase.database.setPersistenceEnabled(true)
    }

    private fun initNotificationChannel() {
        val nm = NotificationManagerCompat.from(this)

        val hanimeDownloadChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Hanime Download").build()
        nm.createNotificationChannel(hanimeDownloadChannel)

        val appUpdateChannel = NotificationChannelCompat.Builder(
            UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("App Update").build()
        nm.createNotificationChannel(appUpdateChannel)
    }
    fun switchLauncher(alias: String) {
        val pm = packageManager

        val allAliases = listOf(
            "com.yenaly.han1meviewer.LauncherAliasDefault",
            "com.yenaly.han1meviewer.LauncherFakeCalc",
            "com.yenaly.han1meviewer.LauncherFakeCornhub",
            "com.yenaly.han1meviewer.LauncherFakeXxt"
        )

        allAliases.forEach { a ->
            val state = if (a == alias)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            pm.setComponentEnabledSetting(
                ComponentName(this, a),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}