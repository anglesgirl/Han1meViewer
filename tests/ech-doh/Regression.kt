package regression

import com.yenaly.han1meviewer.HANIME_BACKUP_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.HANIME_MEDIA_CDN_HOST
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.DohConfig
import com.yenaly.han1meviewer.logic.network.HDns
import com.yenaly.han1meviewer.logic.network.ech.EchDns
import com.yenaly.han1meviewer.logic.network.ech.EchDoh
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

private object Wire {
    fun qname(q: ByteArray): String {
        var p = 12
        val labels = mutableListOf<String>()
        while (p < q.size && q[p].toInt() != 0) {
            val size = q[p++].toInt() and 0xff
            labels += String(q, p, size, Charsets.US_ASCII)
            p += size
        }
        return labels.joinToString(".")
    }
}

private val gatewayUrl = "https://82sew1c85i.cloudflare-gateway.com/dns-query"
private val expectedBootstrapIp = "172.64.229.69"
private val eCHWireFixture = File(testDir, "fixtures/223.5.5.5-cloudflare-ech.com.bin")
private val gatewayRequests = AtomicInteger()
private val unexpectedRequests = mutableListOf<String>()

private fun mockBootstrapClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("unexpected unpinned DNS lookup: $hostname")
    })
    .addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val host = req.url.host
        val dns = req.url.queryParameter("dns")?.let { java.util.Base64.getUrlDecoder().decode(it) }
        val qname = dns?.let(Wire::qname)
        if (req.url.scheme == "https" && host == "82sew1c85i.cloudflare-gateway.com" &&
            req.url.encodedPath == "/dns-query" && qname == "cloudflare-ech.com" &&
            req.header("accept") == "application/dns-message"
        ) {
            gatewayRequests.incrementAndGet()
        } else {
            unexpectedRequests += "${req.url} qname=$qname accept=${req.header("accept")}"
        }
        val body = eCHWireFixture.readBytes()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("content-type", "application/dns-message")
            .body(ResponseBody.create("application/dns-message".toMediaType(), body)).build()
    }).build()

private fun pinProbeClient(connectAddresses: MutableList<String>, requestHosts: MutableList<String>): OkHttpClient {
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
            requestHosts += chain.request().url.host
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

private fun gatewayWireCacheAndConfigTest() {
    configurePreferences()
    expect(DohConfig.selectedPreset().url == gatewayUrl, "production DohConfig preset URL changed unexpectedly")
    expect(DohConfig.bootstrapIps() == listOf(expectedBootstrapIp), "production DoH bootstrap should be exactly $expectedBootstrapIp; got ${DohConfig.bootstrapIps()}")
    expect(DohConfig.resolveUrl() == gatewayUrl, "configured DoH URL did not resolve to the production gateway")
    Preferences.dohBootstrapIps = "203.0.113.71, 2001:db8::71;203.0.113.71\n203.0.113.72"
    expect(DohConfig.bootstrapIps() == listOf("203.0.113.71", "2001:db8::71", "203.0.113.72"), "edited bootstrap IPs were not applied or deduplicated")
    expect(DohConfig.resolveUrl() == gatewayUrl && DohConfig.probeUrl() == gatewayUrl, "editing bootstrap must not change the DoH endpoint")
    Preferences.dohBootstrapIps = ""
    expect(DohConfig.bootstrapIps() == listOf(expectedBootstrapIp), "clearing bootstrap must restore the built-in default")
    check(eCHWireFixture.isFile) { "missing local wire fixture: $eCHWireFixture" }

    EchDoh.invalidateAll()
    gatewayRequests.set(0)
    unexpectedRequests.clear()

    // Let the real OkHttp DNS path run, but replace connect() with a local throwing probe.
    // This proves the production gateway client actually dials the configured bootstrap IP
    // while never opening a socket to the Internet.
    val connectAddresses = mutableListOf<String>()
    val requestHosts = mutableListOf<String>()
    setLazy(EchDoh, "bootstrapClient", pinProbeClient(connectAddresses, requestHosts))
    expect(EchDoh.echConfigList("pin-probe.example") == null, "offline pin probe should stop before receiving a response")
    expect(requestHosts.isNotEmpty() && requestHosts.all { it == "82sew1c85i.cloudflare-gateway.com" }, "pin probe did not request only the configured gateway domain: $requestHosts")
    expect(connectAddresses.isNotEmpty() && connectAddresses.all { it == expectedBootstrapIp }, "gateway DNS bootstrap did not pin every socket attempt to $expectedBootstrapIp: $connectAddresses")

    EchDoh.invalidateAll()
    setLazy(EchDoh, "bootstrapClient", mockBootstrapClient())

    val wire = EchDoh.echConfigList("hanime1.me")
    expect(wire != null && wire.isNotEmpty(), "captured DNS wire response did not yield an ECH config")
    expect(gatewayRequests.get() == 1, "expected one query to the user gateway, got ${gatewayRequests.get()}")
    expect(unexpectedRequests.isEmpty(), "ECH query escaped gateway-only route: $unexpectedRequests")

    val cache = field(EchDoh, "echCache").get(EchDoh) as Map<*, *>
    val entry = cache["cloudflare-ech.com"] ?: error("ECH entry absent from cache")
    val expiresAt = field(entry, "expireAt").getLong(entry)
    val remaining = expiresAt - System.currentTimeMillis()
    expect(remaining in 14_390_000L..14_400_000L, "ECH cache TTL was not four hours: ${remaining}ms")
    val fromCache = EchDoh.echConfigList("different.example")
    expect(fromCache != null && fromCache.contentEquals(wire!!), "cached ECH wire value changed across requested hostnames")
    expect(gatewayRequests.get() == 1, "second ECH lookup should be served from the 4-hour cache")

    EchDoh.invalidateEch("hanime1.me")
    val afterInvalidation = EchDoh.echConfigList("hanime1.me")
    expect(afterInvalidation != null && afterInvalidation.contentEquals(wire), "ECH lookup failed after explicit invalidation")
    expect(gatewayRequests.get() == 2, "explicit invalidateEch did not trigger a fresh gateway request")
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
    gatewayWireCacheAndConfigTest()
    configuredResolverAndFailClosedTest()
    customCdnIpBypassTest()
    hdnsUsesConfiguredGatewayTest()
    println("PASS: ${assertions.get()} assertions; gateway-only ECH wire/cache, all-domain resolver/fail-closed, CDN IP bypass, and HDns configured-gateway path")
}
