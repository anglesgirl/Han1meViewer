package com.yenaly.han1meviewer.analytics

import android.content.Context
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.PostHog

object PostHogManager {
    private const val POSTHOG_KEY = "phc_nK8D285fUri5raFY7RFhztnYGqMukLNR6PfymaUB2R27"
    private const val POSTHOG_HOST = "https://e.anglesya.win"
    private const val APP = "han1meviewer"
    @Volatile private var initialized = false

    fun init(context: Context, enabled: Boolean) {
        if (!enabled || initialized) return
        try {
            val config = PostHogAndroidConfig(apiKey = POSTHOG_KEY, host = POSTHOG_HOST).apply {
                captureScreenViews = false
                captureApplicationLifecycleEvents = false
                captureDeepLinks = false
            }
            PostHogAndroid.setup(context, config)
            initialized = true
            track("app_launch")
        } catch (_: Exception) {}
    }

    fun track(event: String, props: Map<String, Any?> = emptyMap()) {
        if (!initialized) return
        try {
            val full = buildMap {
                put("app", APP)
                props.forEach { (k, v) ->
                    val s = v?.toString()
                    put(k, if (s != null && s.length > 200) s.take(200) + "…" else v)
                }
            }
            PostHog.capture(event, full)
        } catch (_: Exception) {}
    }

    fun disable() {
        if (initialized) { try { PostHog.optOut() } catch (_: Exception) {}; initialized = false }
    }
}
