package com.yenaly.han1meviewer

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import com.yenaly.han1meviewer.diagnostics.Diagnostics
import com.liar.han1meplus.EchHttpClient
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.logic.network.ech.EchProvider
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.han1meviewer.ui.activity.MainActivity
import com.yenaly.han1meviewer.util.AnimeShaders
import com.yenaly.han1meviewer.util.ThemeUtils
import com.developer.crashx.config.CrashConfig
import com.yenaly.yenaly_libs.base.YenalyApplication
import `is`.xyz.mpv.MPVLib
import java.net.ProxySelector

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : YenalyApplication() {

    companion object {
        const val TAG = "HanimeApplication"
    }

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
        Diagnostics.initialize(this)
        // 仅加载已验证的 Plus native ECH 库；网络切换在链路验证后单独开启。
        runCatching { EchHttpClient.init(this) }
            .onFailure { Diagnostics.event("ech_native_load_failure", mapOf("error_type" to it.javaClass.simpleName)) }
        // 尽早安装 Conscrypt：ECH 需要自带 BoringSSL（系统原生 ECH 从 API 37 才有）。
        EchProvider.install()
        // Fork 自用包：预先标记用户须知已接受，避免依赖该标记的初始化流程卡住。
        if (!Preferences.usageNoticeAccepted) Preferences.usageNoticeAccepted = true
        ThemeUtils.applyDarkModeFromPreferences(this)
        if (Preferences.useDynamicColor){
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        ProxySelector.setDefault(HProxySelector())
        HProxySelector.rebuildNetwork()
        initNotificationChannel()
        AppViewModel.getLatestVersion(delayMillis = 200)
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