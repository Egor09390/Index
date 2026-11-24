package io.nekohasekai.sagernet.bg.test

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.ResolveRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume

/** Pin local dial lookups in this instance only, keeping the hostname for SNI and certificate checks. */
internal suspend fun prepareQualityDns(config: JsonObject, network: Network?): Int = coroutineScope {
    val direct = config.array("outbounds").filterIsInstance<JsonObject>()
        .filter { it.string("detour").isBlank() }
        .map { it to listOf(it.string("server")).filter(::isDomain) }
    val wireGuard = config.array("endpoints").filterIsInstance<JsonObject>()
        .filter { it.string("type") == "wireguard" && it.string("detour").isBlank() }
        .map { endpoint ->
            endpoint to endpoint.array("peers").filterIsInstance<JsonObject>()
                .map { it.string("address") }.filter(::isDomain)
        }
    val dials = (direct + wireGuard).filter { (_, hosts) -> hosts.isNotEmpty() }
    val lookups = dials.flatMap { it.second }.distinct().associateWith { host ->
        async {
            withTimeoutOrNull(1800) { resolveQualityHost(network, host) }.orEmpty()
                .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
        }
    }
    val predefined = JsonObject()
    for ((host, result) in lookups) {
        val ips = result.await()
        if (ips.isEmpty()) continue
        predefined[host] = JsonArray().also { addresses -> ips.forEach(addresses::add) }
    }
    if (predefined.isEmpty()) return@coroutineScope lookups.size

    val dns = config.obj("dns")
    val servers = dns.array("servers")
    var tag = "quality-pre-resolved"
    val tags = servers.filterIsInstance<JsonObject>().mapTo(HashSet()) { it.string("tag") }
    while (tag in tags) tag += "-"
    // Keep DNS final selection intact even for a custom config with no default resolver.
    if (servers.isEmpty()) {
        val local = "quality-system-dns"
        servers.add(jsonObjectOf("type" to "local", "tag" to local))
        dns["final"] = local
    }
    servers.add(jsonObjectOf("type" to "hosts", "tag" to tag, "predefined" to predefined))
    dns["servers"] = servers
    config["dns"] = dns
    for ((dial, hosts) in dials) {
        if (!hosts.all(predefined::contains)) continue
        val resolver = dial.obj("domain_resolver")
        resolver["server"] = tag
        dial["domain_resolver"] = resolver
    }
    lookups.size - predefined.size
}

/** Xray dials through the native dns-direct transport, so warm its own cache before the timer. */
internal fun warmQualityXrayDns(core: CoreConfig, instance: Instance): Int {
    val domains = (listOfNotNull(core.xrayConfig) + core.xrayFullConfigs).flatMap { json ->
        JsonInput.parseObject(json).array("outbounds").filterIsInstance<JsonObject>().flatMap { outbound ->
            val settings = outbound.obj("settings")
            if (outbound.obj("streamSettings").obj("sockopt").string("dialerProxy").isNotBlank()) emptyList()
            else listOf(settings.string("address")) +
                (settings.array("vnext").filterIsInstance<JsonObject>() + settings.array("servers").filterIsInstance<JsonObject>())
                    .map { it.string("address") }
        }
    }.filter(::isDomain).distinct()
    if (domains.isEmpty()) return 0
    val request = ResolveRequest().apply { domains.forEach(::addDomain) }
    val results = Mobile.resolveDomains(instance, CoreRuntime.platform, request)
    var resolved = 0
    while (results.hasNext()) if (results.next().addresses().hasNext()) resolved++
    return domains.size - resolved
}

private fun isDomain(host: String): Boolean = host.isNotBlank() && !Hosts.isIpAddress(host)

private suspend fun resolveQualityHost(network: Network?, host: String): List<InetAddress> {
    if (Build.VERSION.SDK_INT >= 29) {
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, Runnable::run, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        continuation.resume(if (rcode == 0) answer else emptyList())
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resume(emptyList())
                    }
                },
            )
        }
    }
    return runInterruptible(Dispatchers.IO) {
        try {
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()
        } catch (_: UnknownHostException) {
            emptyList()
        }
    }
}
