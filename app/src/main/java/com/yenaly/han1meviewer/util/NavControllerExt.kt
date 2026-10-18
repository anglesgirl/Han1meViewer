package com.yenaly.han1meviewer.util

import android.util.Log
import androidx.navigation.NavController

private const val TAG = "NavSafe"

/**
 * 安全的 popBackStack：捕获导航栈竞态导致的 IllegalStateException。
 * 快速连点返回键或页面跳转时序错乱时，NavController 内部状态可能不一致，
 * 直接 pop 会抛 "Attempted to pop" 崩溃。这里吞掉异常返回 false。
 */
fun NavController.popBackStackSafe(): Boolean {
    return try {
        popBackStack()
    } catch (e: IllegalStateException) {
        Log.w(TAG, "popBackStack failed (race): ${e.message}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "popBackStack failed: ${e.message}")
        false
    }
}

/**
 * 带路由的安全 pop，语义同 NavController.popBackStack(route, inclusive)。
 */
fun NavController.popBackStackSafe(route: Any, inclusive: Boolean): Boolean {
    return try {
        popBackStack(route, inclusive)
    } catch (e: IllegalStateException) {
        Log.w(TAG, "popBackStack(route) failed (race): ${e.message}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "popBackStack(route) failed: ${e.message}")
        false
    }
}
