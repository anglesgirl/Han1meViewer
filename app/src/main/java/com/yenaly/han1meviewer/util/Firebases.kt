package com.yenaly.han1meviewer.util

import android.app.Activity
import android.util.Log
import androidx.fragment.app.Fragment

/**
 * Analytics 已在本分支移除，保留空实现以维持调用点不变。
 */
fun Activity.logScreenViewEvent(fragment: Fragment) {
    logScreenViewEvent(fragment.javaClass.simpleName)
}

fun Activity.logScreenViewEvent(screenClassName: String) {
    val screenName = this.javaClass.simpleName + "-" + screenClassName
    Log.d("logScreenViewEvent", "screenName: $screenName")
}
