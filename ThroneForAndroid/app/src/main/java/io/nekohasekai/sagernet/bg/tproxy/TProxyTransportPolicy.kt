package io.nekohasekai.sagernet.bg.tproxy

import io.nekohasekai.sagernet.outbound.json.JsonObject

internal fun requiresQuicFallback(config: JsonObject): Boolean {
    val route = config.obj("route")
    val outbounds = config.array("outbounds").filterIsInstance<JsonObject>()
    val final = route.string("final")
    val outbound = if (final.isEmpty()) outbounds.firstOrNull() else outbounds.find { it.string("tag") == final }
    if (outbound?.string("type") != "naive") return false
    if (outbound.bool("udp_over_tcp") || outbound.obj("udp_over_tcp").bool("enabled")) return false

    val selected = outbound.string("tag")
    return route.array("rules").filterIsInstance<JsonObject>().none { rule ->
        val target = rule.string("outbound")
        if (target.isEmpty() || target == selected) return@none false
        val inbounds = rule.array("inbound").strings() + rule.string("inbound")
        val networks = rule.array("network").strings() + rule.string("network")
        val ports = rule.array("port").mapNotNull { (it as? Number)?.toInt() } +
            listOf(rule.int("port")).filter { it > 0 }
        (inbounds.all(String::isBlank) || "mixed-in" in inbounds || rule.bool("invert")) &&
            (networks.all(String::isBlank) || "udp" in networks || rule.bool("invert")) &&
            (ports.isEmpty() || 443 in ports || rule.bool("invert"))
    }
}
