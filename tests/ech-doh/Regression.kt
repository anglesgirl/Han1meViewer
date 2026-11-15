package regression

import com.yenaly.han1meviewer.HANIME_BACKUP_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.HANIME_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.HDns
import com.yenaly.han1meviewer.logic.network.ech.EchDns
import com.yenaly.han1meviewer.logic.network.ech.EchDoh
import com.yenaly.han1meviewer.logic.network.ech.EchHosts
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

private val assertions = AtomicInteger()
private val testDir = File(System.getProperty("ech.test.dir", ".")).canonicalFile
private fun expect(value: Boolean, message: String) {
    assertions.incrementAndGet()
    check(value) { message }
}

private val gatewayUrl = "https://82sew1c85i.cloudflare-gateway.com/dns-query"
private val expectedBootstrapIp = "172.64.229.69"
private val gatewayRequests = AtomicInteger()
private val queryNames = mutableListOf<String>()
private val unexpectedRequests = mutableListOf<String>()
private val wireA = byteArrayOf(0x00, 0x05, 0xfe.toByte(), 0x0d, 0x00, 0x01, 0x11)
private val wireB = byteArrayOf(0x00, 0x05, 0xfe.toByte(), 0x0d, 0x00, 0x01, 0x22)
private val malformedWire = byteArrayOf(0x00, 0x05, 0xfe.toByte(), 0x0d, 0x00, 0x00, 0x01)

private data class DnsAnswer(val owner: String, val type: Int, val ttl: Int, val data: String)

private fun dnsAnswerJson(owner: String, type: Int, ttl: Int, data: String): String =
    dnsAnswersJson(DnsAnswer(owner, type, ttl, data))

private fun dnsAnswersJson(vararg answers: DnsAnswer): String {
    val answerArray = org.json.JSONArray()
    answers.forEach { record ->
        answerArray.put(
            org.json.JSONObject()
                .put("name", record.owner)
                .put("type", record.type)
                .put("TTL", record.ttl)
                .put("data", record.data),
        )
    }
    return org.json.JSONObject()
        .put("Status", 0)
        .put("Answer", answerArray)
        .toString()
}

private fun responseFor(name: String?): String {
    val echData: (ByteArray) -> String = { wire ->
        val b64 = java.util.Base64.getEncoder().encodeToString(wire)
        "1 . alpn=\"h2\" ech=\"$b64\""
    }
    val answer: (String, Int, Int, ByteArray) -> String = { owner, type, ttl, wire ->
        dnsAnswerJson(owner, type, ttl, echData(wire))
    }
    return when (name) {
        "hanime1.me" -> dnsAnswersJson(
            DnsAnswer("hanime1.me", 5, 60, "x.xn--pn1aul.eu.org."),
            DnsAnswer("x.xn--pn1aul.eu.org", 65, 90, echData(wireA)),
        )
        "different.example" -> answer(name.orEmpty(), 65, 120, wireB)
        "foreign.example" -> answer("other.example", 65, 90, wireA)
        "wrong-type.example" -> answer(name.orEmpty(), 1, 90, wireA)
        "malformed.example" -> answer(name.orEmpty(), 65, 90, malformedWire)
        "zero-ttl.example" -> answer(name.orEmpty(), 65, 0, wireA)
        "loop.example" -> dnsAnswersJson(
            DnsAnswer("loop.example", 5, 60, "loop-alias.example."),
            DnsAnswer("loop-alias.example", 5, 60, "loop.example."),
        )
        else -> org.json.JSONObject().put("Status", 0).put("Answer", org.json.JSONArray()).toString()
    }
}

private fun mockBootstrapClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("unexpected unpinned DNS lookup: $hostname")
    })
    .addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val name = req.url.queryParameter("name")
        val type = req.url.queryParameter("type")
        if (req.url.scheme == "https" && req.url.host == "82sew1c85i.cloudflare-gateway.com" &&
            req.url.encodedPath == "/dns-query" && name != null && type == "HTTPS" &&
            req.header("accept") == "application/dns-json"
        ) {
            gatewayRequests.incrementAndGet()
            queryNames += "$name:$type"
        } else {
            unexpectedRequests += "${req.url} name=$name type=$type accept=${req.header("accept")}"
        }
        val body = responseFor(name)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("content-type", "application/dns-json")
            .body(ResponseBody.create("application/dns-json".toMediaType(), body)).build()
    }).build()

private fun pinProbeClient(
    connectAddresses: MutableList<String>, requestHosts: MutableList<String>, requestQueries: MutableList<String>,
): OkHttpClient {
    val sockets = object : SocketFactory() {
        private fun probeSocket() = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val address = endpoint as InetSocketAddress
                connectAddresses += address.address?.hostAddress ?: address.hostString
                throw IOException("intentional offline bootstrap-pin probe")
            }
        }
        override fun createSocket(): Socket = probeSocket()
        override fun createSocket(host: String, port: Int): Socket = probeSocket()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = probeSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = probeSocket()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = probeSocket()
    }
    return OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(sockets)
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requestHosts += url.host
            requestQueries += "${url.queryParameter("name")}:${url.queryParameter("type")}"
            chain.proceed(chain.request())
        })
        .build()
}

private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
private fun setField(target: Any, name: String, value: Any?) = field(target, name).set(target, value)
private fun setLazy(target: Any, name: String, value: Any?) {
    val delegate = field(target, "${name}\$delegate").get(target)
    delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(delegate, value)
}

private fun configurePreferences() {
    Preferences.useDoH = true
    Preferences.dohPreset = "cf-gateway"
    Preferences.dohCustomUrl = ""
    Preferences.dohBootstrapIps = ""
    Preferences.dohTimeoutSeconds = 5
    Preferences.useBuiltInHosts = false
    Preferences.customHostsData = ""
    Preferences.mediaCdnNodeIps = emptyList()
}

private fun gatewayHostScopedConfigTest() {
    configurePreferences()
    expect(DohConfig.selectedPreset().url == gatewayUrl, "production DohConfig preset URL changed unexpectedly")
    expect(DohConfig.bootstrapIps() == listOf(expectedBootstrapIp), "production DoH bootstrap should be exactly $expectedBootstrapIp; got ${DohConfig.bootstrapIps()}")
    expect(DohConfig.resolveUrl() == gatewayUrl, "configured DoH URL did not resolve to the production gateway")
    Preferences.dohBootstrapIps = "203.0.113.71, 2001:db8::71;203.0.113.71\n203.0.113.72"
    expect(DohConfig.bootstrapIps() == listOf("203.0.113.71", "2001:db8::71", "203.0.113.72"), "edited bootstrap IPs were not applied or deduplicated")
    expect(DohConfig.resolveUrl() == gatewayUrl && DohConfig.probeUrl() == gatewayUrl, "editing bootstrap must not change the DoH endpoint")
    Preferences.dohBootstrapIps = ""
    expect(DohConfig.bootstrapIps() == listOf(expectedBootstrapIp), "clearing bootstrap must restore the built-in default")
    EchDoh.invalidateAll()
    gatewayRequests.set(0)
    queryNames.clear()
    unexpectedRequests.clear()

    // Let the real OkHttp DNS path run, but replace connect() with a local throwing probe.
    // This proves the production gateway client actually dials the configured bootstrap IP
    // while never opening a socket to the Internet.
    val connectAddresses = mutableListOf<String>()
    val requestHosts = mutableListOf<String>()
    val requestQueries = mutableListOf<String>()
    setLazy(EchDoh, "bootstrapClient", pinProbeClient(connectAddresses, requestHosts, requestQueries))
    expect(EchDoh.echConfigList("pin-probe.example") == null, "offline pin probe should stop before receiving a response")
    expect(requestHosts.isNotEmpty() && requestHosts.all { it == "82sew1c85i.cloudflare-gateway.com" }, "pin probe did not request only the configured gateway domain: $requestHosts")
    expect(requestQueries == listOf("pin-probe.example:HTTPS"), "pin probe did not query the requested host's HTTPS record: $requestQueries")
    expect(connectAddresses.isNotEmpty() && connectAddresses.all { it == expectedBootstrapIp }, "gateway DNS bootstrap did not pin every socket attempt to $expectedBootstrapIp: $connectAddresses")

    EchDoh.invalidateAll()
    setLazy(EchDoh, "bootstrapClient", mockBootstrapClient())

    gatewayRequests.set(0)
    queryNames.clear()
    val wire = EchDoh.echConfigList("HANIME1.ME.")
    expect(wire != null && wire.contentEquals(wireA), "CNAME target HTTPS answer did not yield the queried host's ECH config")
    expect(gatewayRequests.get() == 1, "expected one query to the user gateway, got ${gatewayRequests.get()}")
    expect(queryNames == listOf("hanime1.me:HTTPS"), "query did not use the normalized target host: $queryNames")
    expect(unexpectedRequests.isEmpty(), "ECH query escaped gateway-only route: $unexpectedRequests")

    val other = EchDoh.echConfigList("different.example")
    expect(other != null && other.contentEquals(wireB), "second host reused another hostname's ECH config")
    expect(gatewayRequests.get() == 2, "each uncached host must query its own HTTPS record")

    val cache = field(EchDoh, "echCache").get(EchDoh) as Map<*, *>
    expect(cache.keys.containsAll(listOf("hanime1.me", "different.example")), "ECH cache was not keyed by normalized hostname: ${cache.keys}")
    val entry = cache["hanime1.me"] ?: error("target host ECH entry absent from cache")
    val expiresAt = field(entry, "expireAt").getLong(entry)
    val remaining = expiresAt - System.currentTimeMillis()
    expect(remaining in 55_000L..60_000L, "ECH cache did not use the minimum CNAME/HTTPS TTL: ${remaining}ms")
    val fromCache = EchDoh.echConfigList("hanime1.me")
    expect(fromCache != null && fromCache.contentEquals(wire!!), "same-host cached ECH value changed")
    expect(gatewayRequests.get() == 2, "same-host cache lookup unexpectedly queried DoH")

    EchDoh.invalidateEch("HANIME1.ME.")
    val afterInvalidation = EchDoh.echConfigList("hanime1.me")
    expect(afterInvalidation != null && afterInvalidation.contentEquals(wire), "ECH lookup failed after target-host invalidation")
    expect(gatewayRequests.get() == 3, "target-host invalidation did not trigger a fresh query")
    expect(EchDoh.echConfigList("different.example")?.contentEquals(wireB) == true, "invalidating one host evicted another host's config")
    expect(gatewayRequests.get() == 3, "unrelated host cache was not preserved")
}

private fun targetConfigValidationTest() {
    configurePreferences()
    EchDoh.invalidateAll()
    setLazy(EchDoh, "bootstrapClient", mockBootstrapClient())
    gatewayRequests.set(0)
    queryNames.clear()

    expect(EchHosts.isCoreDomain("hanime1.me"), "Hanime core host lost its required-domain classification")
    expect(EchHosts.isProtected("hanime1.me"), "core host should remain statically protected")
    expect(!EchHosts.isProtected(HANIME_MEDIA_CDN_HOST), "media CDN without ECH policy must not be REQUIRED")
    expect(EchHosts.shouldTryEch(HANIME_MEDIA_CDN_HOST), "media hostname should remain eligible for its own ECH lookup")

    expect(EchDoh.echConfigList(HANIME_MEDIA_CDN_HOST) == null, "media host without its own ECH record borrowed another hostname's config")
    val afterFirstMissing = gatewayRequests.get()
    expect(EchDoh.echConfigList(HANIME_MEDIA_CDN_HOST) == null, "host without ECH record unexpectedly gained a config")
    expect(gatewayRequests.get() == afterFirstMissing + 1, "no-config answer was cached beyond its DNS TTL")
    expect(EchDoh.echConfigList("foreign.example") == null, "ECH answer with a different owner name was accepted")
    expect(EchDoh.echConfigList("loop.example") == null, "CNAME loop was followed while looking up ECH")
    expect(EchDoh.echConfigList("wrong-type.example") == null, "non-HTTPS DNS answer was accepted as ECH")
    expect(EchDoh.echConfigList("malformed.example") == null, "malformed ECHConfigList framing was accepted")

    val zeroTtl = EchDoh.echConfigList("zero-ttl.example")
    expect(zeroTtl != null && zeroTtl.contentEquals(wireA), "valid zero-TTL ECH record was not usable for the current lookup")
    val beforeZeroTtlRepeat = gatewayRequests.get()
    expect(EchDoh.echConfigList("zero-ttl.example")?.contentEquals(wireA) == true, "zero-TTL record failed on repeat lookup")
    expect(gatewayRequests.get() == beforeZeroTtlRepeat + 1, "zero-TTL ECH record was cached")
}

private fun configuredResolverAndFailClosedTest() {
    configurePreferences()
    EchDoh.invalidateAll()
    val resolved = InetAddress.getByAddress(byteArrayOf(198.toByte(), 51, 100, 40))
    val queried = mutableListOf<String>()
    val configured = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            queried += hostname
            return listOf(resolved)
        }
    }
    setField(EchDoh, "cachedResolver", configured)
    setField(EchDoh, "cachedResolverUrl", gatewayUrl)

    for (host in listOf("hanime1.me", "media.example", "api.javchu.com")) {
        expect(EchDoh.resolve(host) == listOf(resolved), "configured resolver was not used for $host")
    }
    expect(queried == listOf("hanime1.me", "media.example", "api.javchu.com"), "core/non-core DNS routing differed: $queried")

    EchDoh.invalidateAll()
    queried.clear()
    val failing = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            queried += hostname
            throw UnknownHostException("configured resolver fixture failure")
        }
    }
    setField(EchDoh, "cachedResolver", failing)
    setField(EchDoh, "cachedResolverUrl", gatewayUrl)
    val fallbackCalls = AtomicInteger()
    val systemFallback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            fallbackCalls.incrementAndGet()
            return listOf(resolved)
        }
    }
    val thrown = runCatching { EchDns(systemFallback).lookup("not-cached.example") }.exceptionOrNull()
    expect(thrown is UnknownHostException, "failed DoH lookup must throw UnknownHostException rather than return system DNS results")
    expect(queried == listOf("not-cached.example"), "failed lookup did not reach the configured resolver exactly once: $queried")
    expect(fallbackCalls.get() == 0, "failed DoH lookup improperly used system DNS fallback")
}

private fun customCdnIpBypassTest() {
    configurePreferences()
    EchDoh.invalidateAll()
    val lookups = AtomicInteger()
    val configured = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            lookups.incrementAndGet()
            return listOf(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 20)))
        }
    }
    setField(EchDoh, "cachedResolver", configured)
    setField(EchDoh, "cachedResolverUrl", gatewayUrl)
    Preferences.mediaCdnNodeIps = listOf("192.0.2.44", "192.0.2.45")

    for (host in listOf(HANIME_MEDIA_CDN_HOST, HANIME_BACKUP_MEDIA_CDN_HOST)) {
        val addrs = EchDoh.resolve(host)
        expect(addrs.map { it.hostAddress } == listOf("192.0.2.44", "192.0.2.45"), "custom CDN IPs not returned for $host: $addrs")
    }
    expect(lookups.get() == 0, "custom CDN IPs should bypass configured resolver, resolver calls=${lookups.get()}")
}

private fun hdnsUsesConfiguredGatewayTest() {
    configurePreferences()
    val answer = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 70))
    val queried = mutableListOf<String>()
    val configuredDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            queried += hostname
            return listOf(answer)
        }
    }
    val hdns = HDns()
    val cfgClass = Class.forName("com.yenaly.han1meviewer.logic.network.HDns\$DohRuntimeConfig")
    val ctor = cfgClass.getDeclaredConstructor(String::class.java, List::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val config = ctor.newInstance(gatewayUrl, listOf(expectedBootstrapIp), 5)
    setField(hdns, "cachedDohConfig", config)
    setField(hdns, "cachedDohDns", configuredDns)

    expect(hdns.lookup("ordinary.example") == listOf(answer), "HDns did not return its configured DoH answer")
    expect(queried == listOf("ordinary.example"), "HDns did not route through configured resolver: $queried")
    expect(DohConfig.resolveUrl() == gatewayUrl && DohConfig.bootstrapIps() == listOf(expectedBootstrapIp), "HDns test must use the production gateway configuration")
}

fun main() {
    gatewayHostScopedConfigTest()
    targetConfigValidationTest()
    configuredResolverAndFailClosedTest()
    customCdnIpBypassTest()
    hdnsUsesConfiguredGatewayTest()
    println("PASS: ${assertions.get()} assertions; host-scoped HTTPS ECH records/cache, required-host classification, DoH bootstrap/fail-closed routing, CDN IP bypass, and HDns configured-gateway path")
}
