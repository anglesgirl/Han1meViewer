package com.yenaly.han1meviewer.logic.network.ech

import com.yenaly.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import java.util.Locale

/**
 * ECH hostname policy.
 *
 * Any DNS hostname may be checked for its own HTTPS/SVCB ECHConfig. This is only eligibility
 * to look up a config; it does not mean ECH is required or that another hostname's config may
 * be reused. ECH is sent only when the selected hostname has a valid ECHConfigList.
 *
 * Hanime site hostnames remain fail-closed. A non-core hostname becomes required only after
 * an actual ECH_REJECTED response has provided evidence that the server requires ECH; that
 * runtime state is tracked by ConscryptEch.
 */
object EchHosts {

    /** Whether this host is a DNS hostname eligible for an HTTPS/SVCB ECH lookup. */
    fun shouldTryEch(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').trimEnd('.').lowercase(Locale.ROOT)
        return normalized.isNotEmpty() && ':' !in normalized && !IPV4_LITERAL.matches(normalized)
    }

    /** Whether this is a core Hanime hostname (the site host or one of its subdomains). */
    fun isCoreDomain(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        return HANIME_HOSTNAME.any { h == it || h.endsWith(".$it") }
    }

    /** Compatibility name: only the core site domains are statically ECH-required. */
    fun isProtected(host: String): Boolean = isCoreDomain(host)

    private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
}
