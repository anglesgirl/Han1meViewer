package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences

data class DohPreset(
    val key: String,
    val title: String,
    val url: String,
    val bootstrapIps: List<String>,
)

object DohConfig {
    val presets = listOf(
        DohPreset(
            key = "cf-gateway",
            title = "Cloudflare Gateway",
            url = "https://82sew1c85i.cloudflare-gateway.com/dns-query",
            bootstrapIps = listOf("162.159.36.20", "162.159.36.5"),
        ),
    )

    fun selectedPreset(): DohPreset = presets.firstOrNull { it.key == Preferences.dohPreset } ?: presets.first()

    fun customUrl(): String = Preferences.dohCustomUrl.trim()

    fun bootstrapIps(): List<String> {
        val customBootstrapIps = Preferences.dohBootstrapIps
            .split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (customBootstrapIps.isNotEmpty()) return customBootstrapIps
        if (Preferences.dohPreset == "custom") return emptyList()
        return selectedPreset().bootstrapIps
    }

    fun timeoutSeconds(): Int = Preferences.dohTimeoutSeconds.coerceIn(1, 60)

    fun resolveUrl(): String? {
        if (!Preferences.useDoH) return null
        return when (Preferences.dohPreset) {
            "custom" -> customUrl().takeIf { it.isNotBlank() }
            else -> selectedPreset().url
        }
    }
}
