package com.yenaly.han1meviewer.ui.view.video

import cn.jzvd.JZDataSource
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.ResolutionLinkMap
import com.yenaly.han1meviewer.USER_AGENT

class HanimeDataSource : JZDataSource {

    private val urlsList = mutableListOf<Map.Entry<Any?, Any?>>()

    @Suppress("UNCHECKED_CAST")
    constructor(title: String, resolutionLinkMap: ResolutionLinkMap) : this() {
        this.currentUrlIndex = 0
        urlsList.clear()
        this.urlsMap.also { map ->
            map.clear()
            resolutionLinkMap.mapValuesTo(map) { it.value.link }
            urlsList.addAll(map.entries as Set<Map.Entry<Any?, Any?>>)
        }
        this.title = title
        // CDN 防盗链：视频 CDN（如 javchu 的 t33.cdn2020.com）要求 Referer=站点 + UA 才放行
        // （hanime1 的 CDN 不校验所以以前一直能播；空 headerMap 是 "站点 A 能播、B 崩" 的根因）
        this.headerMap = hashMapOf(
            "Referer" to Preferences.baseUrl.removeSuffix("/"),
            "User-Agent" to USER_AGENT,
        )
        this.looping = false
        this.objects = null
    }

    override fun getKeyFromDataSource(index: Int): String? {
        return urlsList.getOrNull(index)?.key?.toString()
    }

    override fun getValueFromLinkedMap(index: Int): Any? {
        return urlsList.getOrNull(index)?.value
    }

    private constructor() : super("")


}