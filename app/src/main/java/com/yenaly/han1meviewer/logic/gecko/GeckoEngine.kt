package com.yenaly.han1meviewer.logic.gecko

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

object GeckoEngine {
    @Volatile
    private var runtime: GeckoRuntime? = null

    // Han1me 专用 DoH（与 HDns 的 han1me_app 一致），该 DoH 已下发 hanime 的 ECHConfig
    private const val DOH_URI = "https://82sew1c85i.cloudflare-gateway.com/dns-query"

    fun getRuntime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val appContext = context.applicationContext
            val configFile = writeConfigFile(appContext)
            val builder = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(true)
                // 优先用 Builder API 设置 TRR（147 实测存在），YAML 兜底
                .apply {
                    try {
                        setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                        setTrustedRecursiveResolverUri(DOH_URI)
                        Log.i("GeckoEngine", "TRR set via Builder: mode=3 uri=$DOH_URI")
                    } catch (e: Exception) {
                        Log.w("GeckoEngine", "Builder TRR failed: ${e.message}")
                    }
                }
            if (configFile != null) {
                try {
                    builder.configFilePath(configFile.absolutePath)
                    Log.i("GeckoEngine", "use configFilePath=${configFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w("GeckoEngine", "configFilePath failed: ${e.message}", e)
                }
            } else {
                Log.w("GeckoEngine", "configFile null, fallback to defaults")
            }
            val settings = builder.build()
            val rt = GeckoRuntime.create(appContext, settings)
            runtime = rt
            Log.i("GeckoEngine", "GeckoRuntime created, TRR=$DOH_URI")
            return rt
        }
    }

    private fun writeConfigFile(context: Context): File? {
        return try {
            val yaml = buildString {
                appendLine("prefs:")
                // TRR ONLY 强制走 DoH，配合 Gateway 的 ECH 下发实现 SNI 隐藏
                appendLine("  network.trr.mode: 3")
                appendLine("  network.trr.uri: \"$DOH_URI\"")
                appendLine("  network.trr.excluded-domains: \"\"")
                appendLine("  network.trr.allow-rfc1918: true")
                // ECH 默认已开启，无需显式设置；保持原生默认值
                // dom.security.https_only_mode 关闭，避免 http 回跳误拦
                appendLine("  dom.security.https_only_mode: false")
            }
            val f = File(context.filesDir, "geckoview-config.yaml")
            f.writeText(yaml)
            f
        } catch (e: Exception) {
            Log.e("GeckoEngine", "write config failed", e)
            null
        }
    }
}
