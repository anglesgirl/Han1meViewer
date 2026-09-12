package com.yenaly.han1meviewer.util

import android.app.Activity
import android.util.Log
import androidx.fragment.app.Fragment
// ⚠️ Firebase 已整体移除（自用 App 无需统计；占位 google-services.json 会让
// FirebaseInstallations 抛异常导致无限重启）。这里保留函数骨架、只留日志，
// 调用点无需改动，日后要恢复统计再接回来即可。

fun Activity.logScreenViewEvent(fragment: Fragment) {
    logScreenViewEvent(fragment.javaClass.simpleName)
}

fun Activity.logScreenViewEvent(screenClassName: String) {
    val screenName = this@logScreenViewEvent.javaClass.simpleName + "-" + screenClassName
    Log.d("logScreenViewEvent", "screenName: $screenName")
}
