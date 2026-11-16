package com.yenaly.han1meviewer.logic.network.ech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverEpochTest {
    @Test
    fun staleDnsAndEchResponsesCannotBeCachedAfterResolverSwitch() {
        val epoch = ResolverEpoch<String>()
        val dnsCache = mutableMapOf<String, String>()
        val echCache = mutableMapOf<String, String>()
        val oldTicket = epoch.observe("resolver-a")
        val newTicket = epoch.observe("resolver-b")

        // EchDoh performs this check and the write under one monitor for both caches.
        if (epoch.isCurrent("resolver-a", oldTicket)) {
            dnsCache["example.com"] = "old-address"
            echCache["example.com"] = "old-ech-config"
        }
        assertTrue(dnsCache.isEmpty())
        assertTrue(echCache.isEmpty())
        assertTrue(epoch.isCurrent("resolver-b", newTicket))
    }

    @Test
    fun switchingBackToSameResolverDoesNotReviveOldRequests() {
        val epoch = ResolverEpoch<String>()
        val oldA = epoch.observe("a")
        epoch.observe("b")
        val newA = epoch.observe("a")
        assertFalse(epoch.isCurrent("a", oldA))
        assertTrue(epoch.isCurrent("a", newA))
    }

    @Test
    fun explicitInvalidationExpiresInFlightTicketEvenForSameResolver() {
        val epoch = ResolverEpoch<String>()
        val before = epoch.observe("a")
        assertEquals(before, epoch.observe("a"))
        epoch.invalidate()
        val after = epoch.observe("a")
        assertFalse(epoch.isCurrent("a", before))
        assertTrue(epoch.isCurrent("a", after))
    }
}
