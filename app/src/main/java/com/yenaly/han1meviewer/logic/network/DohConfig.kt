package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences

data class DohPreset(
    val key: String,
    val title: String,
    val url: String,
    val bootstrapIps: List<String>,
)

internal data class DohResolverIdentity(
    val url: String,
    val bootstrapIps: List<String>,
    val timeoutSeconds: Int,
)

object DohConfig {
    const val CUSTOM_PRESET_KEY = "custom"

    val presets = listOf(
        DohPreset(
            key = "cf-gateway",
            title = "Cloudflare Gateway",
            url = "https://82sew1c85i.cloudflare-gateway.com/dns-query",
            bootstrapIps = listOf("172.64.229.69"),
        ),
    )

    fun selectedPreset(): DohPreset = presets.firstOrNull { it.key == Preferences.dohPreset } ?: presets.first()

    fun customUrl(): String = Preferences.dohCustomUrl.trim()

    fun configuredUrl(): String? = endpointForSelection(Preferences.dohPreset, Preferences.dohCustomUrl)

    /** Pure selection rule shared by ordinary DNS, ECH HTTPS-record lookups, and tests. */
    internal fun endpointForSelection(presetKey: String, customUrl: String): String? =
        if (presetKey == CUSTOM_PRESET_KEY) {
            customUrl.trim().takeIf { it.isNotEmpty() }
        } else {
            presets.firstOrNull { it.key == presetKey }?.url ?: presets.firstOrNull()?.url
        }

    /** UI initial value: use a saved override verbatim, otherwise show the selected preset's effective default. */
    internal fun bootstrapInputValue(presetKey: String, savedValue: String): String =
        savedValue.takeIf { it.isNotBlank() }
            ?: presets.firstOrNull { it.key == presetKey }?.bootstrapIps?.joinToString(", ").orEmpty()

    private fun parseBootstrapIps(value: String): List<String> = value
        .split(',', '\n', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    fun bootstrapIps(): List<String> {
        val customBootstrapIps = parseBootstrapIps(Preferences.dohBootstrapIps)
        if (customBootstrapIps.isNotEmpty()) return customBootstrapIps
        if (Preferences.dohPreset == CUSTOM_PRESET_KEY) return emptyList()
        return selectedPreset().bootstrapIps
    }

    fun timeoutSeconds(): Int = Preferences.dohTimeoutSeconds.coerceIn(1, 60)

    /** Identity of the effective resolver, including every setting that changes its transport or cache. */
    internal fun resolverIdentity(
        presetKey: String = Preferences.dohPreset,
        customUrl: String = Preferences.dohCustomUrl,
        bootstrapIps: String = Preferences.dohBootstrapIps,
        timeoutSeconds: Int = Preferences.dohTimeoutSeconds,
    ): DohResolverIdentity? {
        val url = endpointForSelection(presetKey, customUrl) ?: return null
        val savedBootstrapIps = parseBootstrapIps(bootstrapIps)
        val effectiveBootstrapIps = when {
            savedBootstrapIps.isNotEmpty() -> savedBootstrapIps
            presetKey == CUSTOM_PRESET_KEY -> emptyList()
            else -> presets.firstOrNull { it.key == presetKey }?.bootstrapIps
                ?: presets.firstOrNull()?.bootstrapIps.orEmpty()
        }
        return DohResolverIdentity(
            url = url,
            bootstrapIps = effectiveBootstrapIps,
            timeoutSeconds = timeoutSeconds.coerceIn(1, 60),
        )
    }

    fun resolveUrl(): String? {
        if (!Preferences.useDoH) return null
        return configuredUrl()
    }

    /** Probe and ECH lookups use the selected endpoint even when the ordinary DoH toggle is off. */
    fun probeUrl(): String = configuredUrl() ?: selectedPreset().url
}
