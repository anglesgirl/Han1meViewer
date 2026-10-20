package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.StubCounters

object DohConfig {
    data class Preset(val url: String)
    @JvmStatic fun timeoutSeconds(): Int { StubCounters.timeoutSeconds++; return 5 }
    @JvmStatic fun selectedPreset(): Preset { StubCounters.selectedPreset++; return Preset("https://preset.invalid/dns-query") }
    @JvmStatic fun bootstrapIps(): List<String> { StubCounters.bootstrapIps++; return listOf("192.0.2.1") }
    @JvmStatic fun resolveUrl(): String? { StubCounters.selectedPreset++; return null }
}
