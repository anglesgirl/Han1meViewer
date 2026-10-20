package regression

import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.StubCounters
import com.yenaly.han1meviewer.logic.network.HDns
import com.yenaly.han1meviewer.logic.network.ech.ConscryptEch
import com.yenaly.han1meviewer.logic.network.ech.EchDoh
import com.yenaly.han1meviewer.logic.network.ech.EchDns
import com.yenaly.han1meviewer.logic.network.ech.EchHosts
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val testDir = File(System.getProperty("ech.test.dir", ".")).canonicalFile
private val endpointIps = listOf("223.5.5.5", "223.6.6.6", "1.12.12.12", "120.53.53.53", "101.198.193.29", "101.198.192.33")
private val assertions = AtomicInteger()
private fun checkThat(ok: Boolean, message: String) { assertions.incrementAndGet(); check(ok) { message } }

private object Wire {
    fun queryName(q: ByteArray): String {
        var p = 12
        val labels = mutableListOf<String>()
        while (p < q.size && q[p].toInt() != 0) {
            val n = q[p++].toInt() and 255
            labels += String(q, p, n, Charsets.US_ASCII); p += n
        }
        return labels.joinToString(".")
    }
    fun answer(name: String, withEch: Boolean): ByteArray {
        val qname = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() }.toByteArray() + byteArrayOf(0)
        val rdata = if (withEch) byteArrayOf(0, 1, 0, 0, 0, 5, 0, 3, 1, 2, 3) else byteArrayOf(0, 1, 0)
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 65, 0, 1, 0, 0, 0, 60, (rdata.size shr 8).toByte(), rdata.size.toByte()) + rdata
        return byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) +
            qname + byteArrayOf(0, 65, 0, 1) + answer
    }
}

private data class ProbeState(
    val mode: String = "fixture",
    val requests: AtomicInteger = AtomicInteger(),
    val primary: AtomicInteger = AtomicInteger(),
    val secondary: AtomicInteger = AtomicInteger(),
    val errors: AtomicInteger = AtomicInteger(),
    val ips: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    val sources: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    val violations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    val slowFirstMs: Long = 0,
)
@Volatile private lateinit var state: ProbeState

private fun fixture(ip: String, host: String): ByteArray {
    val direct = File(testDir, "fixtures/$ip-$host.bin")
    // The Tencent source fixture is intentionally allowed to reuse the captured Alibaba response.
    val file = if (direct.isFile) direct else File(testDir, "fixtures/223.5.5.5-$host.bin")
    check(file.isFile) { "missing fixture $file" }
    return file.readBytes()
}

private fun mockClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("ECH DoH must use an IP literal: $hostname") })
    .followRedirects(false).followSslRedirects(false).addInterceptor(Interceptor { chain ->
        val request = chain.request()
        state.requests.incrementAndGet()
        val ip = request.url.host
        state.ips.add(ip)
        if (request.url.scheme != "https") state.violations.add("non-HTTPS URL: ${request.url}")
        if (ip !in endpointIps) state.violations.add("not an allowlisted literal IP: ${request.url}")
        if (request.url.encodedPath != "/dns-query") state.violations.add("unexpected path: ${request.url.encodedPath}")
        val encoded = request.url.queryParameter("dns") ?: error("missing wire DNS query")
        val qname = Wire.queryName(java.util.Base64.getUrlDecoder().decode(encoded))
        if (qname !in setOf("cloudflare-ech.com", "crypto.cloudflare.com")) state.violations.add("unexpected DNS QNAME: $qname")
        state.sources.add(qname)
        if (qname == "cloudflare-ech.com") state.primary.incrementAndGet() else state.secondary.incrementAndGet()

        var code = 200
        val body: ByteArray
        when (state.mode) {
            "throw-first" -> {
                if (state.errors.compareAndSet(0, 1)) throw IOException("synthetic first-endpoint failure")
                body = fixture(ip, qname)
            }
            "slow-first-fail" -> {
                if (ip == endpointIps.first() && state.errors.compareAndSet(0, 1)) {
                    Thread.sleep(state.slowFirstMs)
                    throw IOException("synthetic slow first-endpoint failure")
                }
                body = fixture(ip, qname)
            }
            "fail-first-ip" -> {
                if (ip == endpointIps.first() && state.errors.compareAndSet(0, 1)) code = 503
                body = if (code == 200) fixture(ip, qname) else ByteArray(0)
            }
            "missing-primary" -> {
                body = if (qname == "cloudflare-ech.com") Wire.answer(qname, false) else fixture(ip, qname)
            }
            "fail-all" -> { code = 503; body = ByteArray(0) }
            "redirect" -> { code = 302; body = ByteArray(0) }
            else -> body = fixture(ip, qname)
        }
        val rb = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("mock")
            .body(ResponseBody.create("application/dns-message".toMediaType(), body))
        if (code == 302) rb.header("Location", "https://trusted.invalid/dns-query")
        rb.build()
    }).build()

private fun objectInstance(): Any = EchDoh
private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
private fun setField(target: Any, name: String, value: Any?) { field(target, name).set(target, value) }
private fun getField(target: Any, name: String): Any? = field(target, name).get(target)
private fun lazyValue(name: String, value: Any?) {
    val lazy = getField(objectInstance(), "${name}\$delegate")!!
    lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(lazy, value)
}
private fun getLazyValue(name: String): Any? {
    val lazy = getField(objectInstance(), "${name}\$delegate")!!
    return lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }.get(lazy)
}
private fun setup(mode: String = "fixture", slowFirstMs: Long = 0) {
    EchDoh.invalidateAll()
    state = ProbeState(mode = mode, slowFirstMs = slowFirstMs)
    lazyValue("echIpClient", mockClient())
    lazyValue("publicResolvers", null)
    setField(objectInstance(), "cachedResolver", null)
    setField(objectInstance(), "cachedResolverUrl", null)
    Preferences.dohCustomUrl = "https://trusted.invalid/dns-query"
    Preferences.dohUrlReads = 0
    StubCounters.selectedPreset = 0; StubCounters.bootstrapIps = 0; StubCounters.timeoutSeconds = 0
}
private fun verifyMock() = checkThat(state.violations.isEmpty(), "mock request invariant failed: ${state.violations}")

private fun dohRoutingTest() {
    setup()
    var trusted = 0
    val trustedAddrs = listOf(InetAddress.getByName("192.0.2.44"))
    val trustedDns = object : Dns { override fun lookup(hostname: String): List<InetAddress> { trusted++; return trustedAddrs } }
    setField(objectInstance(), "cachedResolver", trustedDns)
    setField(objectInstance(), "cachedResolverUrl", "https://trusted.invalid/dns-query")
    val publicCalls = mutableListOf<String>()
    val publicDns = object : Dns { override fun lookup(hostname: String): List<InetAddress> { publicCalls += hostname; return listOf(InetAddress.getByName("198.51.100.7")) } }
    lazyValue("publicResolvers", endpointIps.associateWith { publicDns })

    for (host in listOf("JAVCHU.COM.", "api.javchu.com")) checkThat(EchDoh.resolve(host) == trustedAddrs, "core hostname $host must use trusted resolver")
    checkThat(trusted == 2, "expected trusted lookups for normalized core names, got $trusted")
    checkThat(publicCalls.isEmpty(), "core names leaked to public resolver: $publicCalls")
    for (host in listOf("eviljavchu.com", "javchu.com.evil.invalid")) checkThat(EchDoh.resolve(host).isNotEmpty(), "public non-core $host should resolve")
    checkThat(publicCalls == listOf("eviljavchu.com", "javchu.com.evil.invalid"), "suffix boundary or public routing failed: $publicCalls")
    checkThat(Preferences.dohUrlReads == 2, "core lookups should use the trusted DoH URL exactly once each; public lookups must not read it")
    checkThat(EchHosts.isCoreDomain("JAVCHU.COM."), "case/trailing-dot root should be core")
    checkThat(EchHosts.isCoreDomain("api.javchu.com"), "subdomain should be core")
    checkThat(!EchHosts.isCoreDomain("eviljavchu.com"), "lookalike suffix must not be core")
    checkThat(!EchHosts.isCoreDomain("javchu.com.evil.invalid"), "embedded suffix must not be core")
    checkThat(EchHosts.shouldTryEch("anything.example"), "all hostnames should try ECH")
    checkThat(EchHosts.isProtected("anything.example"), "compatibility alias must try ECH")
}

private fun realFixtureFallbackAndCacheTest() {
    setup("missing-primary")
    val result = EchDoh.echConfigList("any-host.example")
    checkThat(result != null && result.size >= 2, "real secondary-source DNS fixture must parse into ECH config")
    checkThat(state.primary.get() > 0 && state.secondary.get() > 0, "missing primary ECH must fall through to crypto.cloudflare.com")
    checkThat(Preferences.dohUrlReads == 0 && StubCounters.bootstrapIps == 0 && StubCounters.selectedPreset == 0 && StubCounters.timeoutSeconds == 0, "ECH query must not touch DoH config / user gateway")
    val count = state.requests.get()
    checkThat(EchDoh.echConfigList("different-host.example")!!.contentEquals(result!!), "ECH cache should be shared across requested hosts")
    checkThat(state.requests.get() == count, "cached ECH value unexpectedly caused another request")
    val cache = getField(objectInstance(), "echCache") as Map<*, *>
    val entry = cache["cloudflare-ech.com"]!!
    val expiry = entry.javaClass.getDeclaredField("expireAt").apply { isAccessible = true }.getLong(entry)
    val remaining = expiry - System.currentTimeMillis()
    checkThat(remaining in 3_590_000L..3_600_000L, "ECH cache expiration is not approximately one hour: ${remaining}ms")
    EchDoh.invalidateEch("anything.example")
    checkThat(EchDoh.echConfigList("anything.example") != null, "fetch after explicit invalidation failed")
    checkThat(state.requests.get() > count, "invalidateEch did not cause a new DoH retrieval")
    verifyMock()
}

private fun exceptionAndFailClosedTest() {
    setup("throw-first")
    checkThat(EchDoh.echConfigList("host") != null, "endpoint exception must continue to next endpoint/source")
    checkThat(state.errors.get() == 1 && state.requests.get() == 2, "exception must skip the failed IP's second source; expected 2 requests, got ${state.requests.get()}")
    checkThat(state.ips.toList() == listOf(endpointIps[0], endpointIps[1]), "exception continuation order differed: ${state.ips}")

    setup("fail-all")
    checkThat(EchDoh.echConfigList("host") == null, "all failed endpoints should return null")
    checkThat(state.requests.get() == 6, "must attempt primary source on all 6 IPs only (got ${state.requests.get()})")
    checkThat(state.primary.get() == 6 && state.secondary.get() == 0, "HTTP failures should not retry the second ECH source on the same IP")
    checkThat(EchDoh.echConfigList("host") == null && state.requests.get() == 6, "ECH failure cooldown should suppress an immediate retry")
    checkThat(Preferences.dohUrlReads == 0 && StubCounters.bootstrapIps == 0 && StubCounters.selectedPreset == 0 && StubCounters.timeoutSeconds == 0, "all-failed ECH improperly fell back to user gateway/bootstrap")
    val client = getLazyValue("echIpClient") as OkHttpClient
    checkThat(!client.followRedirects && !client.followSslRedirects, "ECH client must have redirects disabled")
    checkThat(runCatching { client.dns.lookup("dns-gateway.invalid") }.exceptionOrNull() is UnknownHostException, "ECH client must reject DNS hostname resolution")
    setup("redirect")
    checkThat(EchDoh.echConfigList("host") == null, "redirect-only endpoints should not produce a result")
    checkThat(state.requests.get() == 6, "redirect must not be followed or second source attempted; got ${state.requests.get()}")
    checkThat(Preferences.dohUrlReads == 0, "redirect path touched user DoH URL")
    verifyMock()
}

private fun concurrentEchSingleflightTest() {
    setup()
    val workers = 16
    val ready = CountDownLatch(workers)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val futures = (0 until workers).map { pool.submit(Callable {
            ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); EchDoh.echConfigList("parallel-$it.example")
        }) }
        check(ready.await(5, TimeUnit.SECONDS)) { "ECH workers did not become ready" }
        start.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        checkThat(results.all { it != null && it.contentEquals(results.first()!!) }, "concurrent ECH fetch callers did not get the same config")
        checkThat(state.requests.get() == 1 && state.primary.get() == 1, "concurrent ECH fetch should make exactly one HTTP request; got ${state.requests.get()}")
        verifyMock()
    } finally { pool.shutdownNow() }
}

private fun concurrentDnsSingleflightTest() {
    setup()
    val lookups = AtomicInteger()
    val answer = InetAddress.getByName("203.0.113.77")
    val sharedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            lookups.incrementAndGet()
            Thread.sleep(120)
            return listOf(answer)
        }
    }
    lazyValue("publicResolvers", endpointIps.associateWith { sharedDns })
    val workers = 16
    val ready = CountDownLatch(workers)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val futures = (0 until workers).map { pool.submit(Callable {
            ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); EchDoh.resolve(if (it % 2 == 0) "Concurrent.Example." else "concurrent.example")
        }) }
        check(ready.await(5, TimeUnit.SECONDS)) { "DNS workers did not become ready" }
        start.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        checkThat(results.all { it == listOf(answer) }, "same-domain concurrent DNS callers should receive the same resolution")
        checkThat(lookups.get() == 1, "same-domain concurrent DNS should perform one upstream lookup; got ${lookups.get()}")
    } finally { pool.shutdownNow() }
}

private fun preferredEndpointReuseTest() {
    setup("fail-first-ip")
    checkThat(EchDoh.echConfigList("host") != null, "should recover from initial endpoint HTTP failure")
    checkThat(state.ips.take(2) == listOf(endpointIps[0], endpointIps[1]), "expected initial failure then second endpoint: ${state.ips}")
    val count = state.requests.get()
    EchDoh.invalidateEch("host")
    checkThat(EchDoh.echConfigList("host") != null, "refetch via preferred healthy endpoint failed")
    checkThat(state.requests.get() == count + 1 && state.ips.last() == endpointIps[1], "successful endpoint should be reused without retrying the failed first endpoint: ${state.ips}")
    verifyMock()
}

private fun slowEndpointFailureAndSwitchReuseTest() {
    val delayMs = 450L
    setup("slow-first-fail", delayMs)
    val start = System.nanoTime()
    checkThat(EchDoh.echConfigList("host") != null, "slow first endpoint failure should switch to a healthy endpoint")
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    checkThat(state.requests.get() == 2 && state.ips.toList() == listOf(endpointIps[0], endpointIps[1]), "slow transport failure should be paid once, then switch to next IP: ${state.ips}")
    checkThat(state.sources.toSet() == setOf("cloudflare-ech.com"), "slow transport failure must not wait for another source on the same IP: ${state.sources}")
    checkThat(elapsedMs in (delayMs - 100)..(delayMs + 1200), "slow endpoint should incur one bounded wait, observed ${elapsedMs}ms")
    val count = state.requests.get()
    EchDoh.invalidateEch("host")
    val retryStart = System.nanoTime()
    checkThat(EchDoh.echConfigList("host") != null, "request after slow failure should use the healthy preferred endpoint")
    val retryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStart)
    checkThat(state.requests.get() == count + 1 && state.ips.last() == endpointIps[1], "healthy endpoint was not reused after failure: ${state.ips}")
    checkThat(retryMs < 300, "reused healthy endpoint unexpectedly paid another slow wait (${retryMs}ms)")
    println("TIMING: injected first-endpoint failure=${elapsedMs}ms (one ${delayMs}ms pause); successful preferred-endpoint refetch=${retryMs}ms")
    verifyMock()
}

private fun nxdomainDoesNotCoolSharedEndpointTest() {
    setup()
    val publicLookups = AtomicInteger()
    val nxdomain = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            publicLookups.incrementAndGet()
            throw UnknownHostException("synthetic NXDOMAIN")
        }
    }
    lazyValue("publicResolvers", endpointIps.associateWith { nxdomain })
    checkThat(EchDoh.resolve("missing.example").isEmpty(), "NXDOMAIN should resolve to empty without system DNS fallback")
    checkThat(publicLookups.get() == endpointIps.size, "NXDOMAIN should try all public resolvers")
    checkThat(EchDoh.echConfigList("host") != null, "public DNS NXDOMAIN must not cool endpoints shared with ECH")
    checkThat(state.ips.firstOrNull() == endpointIps.first(), "ECH should still use the first healthy endpoint after NXDOMAIN: ${state.ips}")
    verifyMock()
}

private fun coreDnsRemainsFailClosedDuringEndpointCooldownTest() {
    setup("fail-all")
    checkThat(EchDoh.echConfigList("seed") == null && state.requests.get() == 6, "setup should put every public ECH endpoint in failure cooldown")
    val trusted = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("trusted DoH unavailable") }
    setField(objectInstance(), "cachedResolver", trusted)
    setField(objectInstance(), "cachedResolverUrl", "https://trusted.invalid/dns-query")
    val publicLookups = AtomicInteger()
    val public = object : Dns { override fun lookup(hostname: String): List<InetAddress> { publicLookups.incrementAndGet(); return listOf(InetAddress.getByName("198.51.100.23")) } }
    lazyValue("publicResolvers", endpointIps.associateWith { public })
    checkThat(EchDoh.resolve("api.javchu.com").isEmpty(), "core DNS should fail closed when trusted DNS fails")
    val fallbackLookups = AtomicInteger()
    val fallback = object : Dns { override fun lookup(hostname: String): List<InetAddress> { fallbackLookups.incrementAndGet(); return listOf(InetAddress.getByName("192.0.2.88")) } }
    checkThat(runCatching { EchDns(fallback).lookup("api.javchu.com") }.exceptionOrNull() is UnknownHostException, "EchDns should throw instead of falling back when core trusted DNS fails")
    checkThat(publicLookups.get() == 0 && fallbackLookups.get() == 0, "core lookup must not use cooled public endpoints or system fallback")
}

private fun publicDnsFailureNoGatewayTest() {
    setup()
    val publicLookups = AtomicInteger()
    val emptyDns = object : Dns { override fun lookup(hostname: String): List<InetAddress> { publicLookups.incrementAndGet(); return emptyList() } }
    lazyValue("publicResolvers", endpointIps.associateWith { emptyDns })
    checkThat(EchDoh.resolve("not-core.invalid").isEmpty(), "all public DNS failures must return empty")
    checkThat(publicLookups.get() == 6, "public DNS should try all six independent resolvers")
    checkThat(Preferences.dohUrlReads == 0, "public failure must not fallback to trusted gateway")
}

private fun legacyHDnsRoutingTest() {
    setup()
    val publicCalls = mutableListOf<String>()
    val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> { publicCalls += hostname; return listOf(InetAddress.getByName("203.0.113.19")) }
    }
    lazyValue("publicResolvers", endpointIps.associateWith { publicDns })
    val dns = HDns()
    checkThat(dns.lookup("legacy-image.invalid").single().hostAddress == "203.0.113.19", "HDns non-core should resolve through public-only EchDoh")
    checkThat(publicCalls == listOf("legacy-image.invalid"), "HDns non-core missed public resolver: $publicCalls")
    checkThat(Preferences.dohUrlReads == 0 && StubCounters.bootstrapIps == 0 && StubCounters.timeoutSeconds == 0, "HDns non-core path must not read the user's DoH settings")
    Preferences.useBuiltInHosts = true
    checkThat(dns.lookup("javchu.com").isNotEmpty(), "built-in core-host branch must retain precedence")
    checkThat(dns.lookup("www.getchu.com").size == 2, "GETCHU hardcoded branch regression")
    checkThat(publicCalls == listOf("legacy-image.invalid"), "core/GETCHU leaked into public resolvers: $publicCalls")
    checkThat(StubCounters.selectedPreset == 0 && StubCounters.bootstrapIps == 0, "HDns early branches unexpectedly resolved configured DoH")
}

fun main() {
    val suite = listOf(
        "core-vs-public DNS and hostname normalization" to ::dohRoutingTest,
        "real DNS fixture fallback, parsing, shared cache, 1h expiry, invalidation" to ::realFixtureFallbackAndCacheTest,
        "endpoint errors, per-IP HTTP failure, cooldown, fail-closed and redirects" to ::exceptionAndFailClosedTest,
        "concurrent ECH singleflight" to ::concurrentEchSingleflightTest,
        "concurrent same-domain DNS singleflight" to ::concurrentDnsSingleflightTest,
        "successful endpoint preference and reuse" to ::preferredEndpointReuseTest,
        "one slow endpoint wait, failover and healthy endpoint reuse" to ::slowEndpointFailureAndSwitchReuseTest,
        "public NXDOMAIN does not cool shared ECH endpoints" to ::nxdomainDoesNotCoolSharedEndpointTest,
        "core DNS stays fail-closed during public endpoint cooldown" to ::coreDnsRemainsFailClosedDuringEndpointCooldownTest,
        "public DNS exhaustion without gateway fallback" to ::publicDnsFailureNoGatewayTest,
        "legacy HDns public routing and existing bypass precedence" to ::legacyHDnsRoutingTest,
    )
    val completed = mutableListOf<String>()
    for ((name, test) in suite) {
        try { test(); completed += name; println("PASS: $name") }
        catch (t: Throwable) { System.err.println("FAIL: $name: ${t.stackTraceToString()}"); throw t }
    }
    println("RESULT: ${completed.size}/${suite.size} suites passed; ${assertions.get()} assertions")
}
