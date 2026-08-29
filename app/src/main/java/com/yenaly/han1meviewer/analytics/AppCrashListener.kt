package com.yenaly.han1meviewer.analytics

import com.developer.crashx.CrashActivity

class AppCrashListener : CrashActivity.EventListener {
    override fun onLaunchErrorActivity() {
        PostHogManager.track("app_crash")
    }
    override fun onRestartAppFromErrorActivity() {}
    override fun onCloseAppFromErrorActivity() {}
}
