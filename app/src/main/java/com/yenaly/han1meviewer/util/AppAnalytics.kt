package com.yenaly.han1meviewer.util

/**
 * 自有行为统计接入点（clean-ech）。
 *
 * 上游的 Firebase Analytics 已默认禁用（见 Preferences.isAnalyticsEnabled）。
 * 这里预留统一入口，后续接入自有统计服务时实现具体上报逻辑。
 * 当前为空实现：调用方可以照常打点，不会产生任何上报。
 *
 * 注意：诊断日志（log.anglesgirl.eu.org）是另一套通道，用于排障，
 * 不要与行为统计混用。
 */
object AppAnalytics {

    /**
     * 上报自定义事件。
     * @param name 事件名
     * @param params 事件参数（字符串/数字/布尔）
     */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap()) {
        // TODO: 接入自有统计服务后实现
    }

    /**
     * 上报页面浏览。
     * @param screenName 页面名
     * @param screenClass 页面类名
     */
    fun logScreenView(screenName: String, screenClass: String) {
        // TODO: 接入自有统计服务后实现
    }
}
