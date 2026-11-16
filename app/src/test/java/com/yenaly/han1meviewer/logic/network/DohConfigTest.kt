package com.yenaly.han1meviewer.logic.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DohConfigTest {
    @Test
    fun customPresetUsesSavedEditableUrl() {
        assertEquals(
            "https://resolver.example/dns-query",
            DohConfig.endpointForSelection("custom", "  https://resolver.example/dns-query  "),
        )
        assertNull(DohConfig.endpointForSelection("custom", "  "))
    }

    @Test
    fun namedPresetIgnoresRetainedCustomUrl() {
        val selected = DohConfig.endpointForSelection("cf-gateway", "https://old-custom.example/dns-query")
        assertEquals("https://82sew1c85i.cloudflare-gateway.com/dns-query", selected)
    }

    @Test
    fun switchingBackToCustomKeepsTheSavedValueAvailable() {
        val savedCustomUrl = "https://my-resolver.example/dns-query"
        assertEquals(
            "https://82sew1c85i.cloudflare-gateway.com/dns-query",
            DohConfig.endpointForSelection("cf-gateway", savedCustomUrl),
        )
        assertEquals(savedCustomUrl, DohConfig.endpointForSelection("custom", savedCustomUrl))
    }

    @Test
    fun bootstrapInputShowsEffectiveDefaultAndPreservesSavedOverride() {
        assertEquals("172.64.229.69", DohConfig.bootstrapInputValue("cf-gateway", ""))
        assertEquals("192.0.2.44", DohConfig.bootstrapInputValue("cf-gateway", "192.0.2.44"))
        assertEquals("", DohConfig.bootstrapInputValue("custom", ""))
    }

    @Test
    fun resolverIdentityChangesOnlyWhenTheEffectiveConfigurationChanges() {
        val named = DohConfig.resolverIdentity("cf-gateway", "https://stale.example/dns-query", "", 10)
        val namedWithAnotherSavedCustom = DohConfig.resolverIdentity(
            "cf-gateway",
            "https://different-stale.example/dns-query",
            "",
            10,
        )
        assertEquals(named, namedWithAnotherSavedCustom)

        val custom = DohConfig.resolverIdentity("custom", "https://my-resolver.example/dns-query", "192.0.2.1", 10)
        assertNotEquals(custom, named)
        assertNotEquals(
            custom,
            DohConfig.resolverIdentity("custom", "https://other-resolver.example/dns-query", "192.0.2.1", 10),
        )
        assertNotEquals(
            custom,
            DohConfig.resolverIdentity("custom", "https://my-resolver.example/dns-query", "192.0.2.2", 10),
        )
        assertNotEquals(
            custom,
            DohConfig.resolverIdentity("custom", "https://my-resolver.example/dns-query", "192.0.2.1", 15),
        )
    }
}
