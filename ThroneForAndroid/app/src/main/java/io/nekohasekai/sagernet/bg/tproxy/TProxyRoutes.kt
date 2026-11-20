package io.nekohasekai.sagernet.bg.tproxy

import java.io.Closeable

/** Owns only this session's policy rules, routes and mangle chains. */
internal class TProxyRoutes(
    private val uid: Int,
    private val port: Int,
    private val bypassRanges: List<String>,
    private val run: (List<String>) -> Unit,
) : Closeable {
    private val undo = ArrayDeque<List<String>>()
    private val output = "THRONE_TPROXY_OUT"
    private val prerouting = "THRONE_TPROXY_IN"
    private val table = (100_000L + uid).toString()

    private data class Family(
        val selector: String,
        val firewall: String,
        val localhost: String,
        val multicast: String,
        val defaultRoute: String,
    ) {
        fun firewall(vararg args: String) = listOf("/system/bin/$firewall", "-w", "2", "-t", "mangle") + args
        fun ip(vararg args: String) = listOf("/system/bin/ip", selector) + args
    }

    private val families = listOf(
        Family("-4", "iptables", "127.0.0.1", "224.0.0.0/4", "0.0.0.0/0"),
        Family("-6", "ip6tables", "::1", "ff00::/8", "::/0"),
    )

    @Synchronized
    fun start() {
        check(undo.isEmpty())
        try {
            for (family in families) prepare(family)
            for (family in families) {
                hook(family, "PREROUTING", "-i", "lo", "-m", "mark", "--mark", MARK, "-j", prerouting)
            }
            for (family in families) hook(family, "OUTPUT", "-j", output)
        } catch (error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun prepare(family: Family) {
        for (chain in listOf(output, prerouting)) {
            run(family.firewall("-N", chain))
            undo.addLast(family.firewall("-X", chain))
            undo.addLast(family.firewall("-F", chain))
        }
        val route = arrayOf("local", family.defaultRoute, "dev", "lo", "table", table)
        apply(family.ip("route", "add", *route), family.ip("route", "del", *route))
        val rule = arrayOf("pref", PRIORITY, "fwmark", MARK, "lookup", table)
        apply(family.ip("rule", "add", *rule), family.ip("rule", "del", *rule))

        fun outputRule(vararg args: String) = run(family.firewall("-A", output, *args))
        outputRule("-o", "lo", "-j", "RETURN")
        outputRule("-m", "owner", "--uid-owner", "0", "-j", "RETURN")
        outputRule("-m", "owner", "--uid-owner", uid.toString(), "-j", "RETURN")
        outputRule("-d", family.multicast, "-j", "RETURN")
        if (family.selector == "-4") outputRule("-d", "255.255.255.255/32", "-j", "RETURN")

        // Replies from a transparent socket must not enter the listener again.
        run(family.firewall("-A", prerouting, "-m", "conntrack", "--ctdir", "REPLY", "-j", "RETURN"))

        // DNS to a LAN resolver still reaches Throne's DNS route before the LAN exclusions.
        for (protocol in listOf("tcp", "udp")) {
            outputRule("-p", protocol, "--dport", "53", "-j", "MARK", "--set-xmark", MARK)
        }
        outputRule("-m", "mark", "--mark", MARK, "-j", "RETURN")
        for (range in bypassRanges) {
            if (range.contains(':') == (family.selector == "-6")) outputRule("-d", range, "-j", "RETURN")
        }
        for (protocol in listOf("tcp", "udp")) {
            outputRule("-p", protocol, "-j", "MARK", "--set-xmark", MARK)
            run(family.firewall(
                "-A", prerouting, "-p", protocol, "-j", "TPROXY",
                "--on-ip", family.localhost, "--on-port", port.toString(), "--tproxy-mark", MARK,
            ))
        }
    }

    /** Report counts from our own rules and omit addresses and packet data. */
    fun packetCounts(query: (List<String>) -> String): String = families.joinToString(
        prefix = "Packets intercepted by TPROXY: ",
    ) { family ->
        val listing = query(family.firewall("-L", prerouting, "-n", "-v", "-x"))
        val counts = listing.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(WHITESPACE, limit = 5)
            if (fields.getOrNull(2) == "TPROXY") {
                val protocol = fields.getOrNull(3)
                val count = fields.firstOrNull()?.toLongOrNull()
                if (protocol != null && count != null) "$protocol=$count" else null
            } else null
        }.toList()
        "IPv${family.selector.last()} ${counts.joinToString(" ")}"
    }

    private fun hook(family: Family, chain: String, vararg rule: String) {
        apply(family.firewall("-I", chain, "1", *rule), family.firewall("-D", chain, *rule))
    }

    private fun apply(command: List<String>, remove: List<String>) {
        run(command)
        undo.addLast(remove)
    }

    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        val failedCommands = ArrayDeque<List<String>>()
        while (undo.isNotEmpty()) {
            val command = undo.removeLast()
            try {
                run(command)
            } catch (error: Throwable) {
                failedCommands.addFirst(command)
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        undo.addAll(failedCommands)
        failure?.let { throw it }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MARK = "0x10000000/0x10000000"
        const val PRIORITY = "9000"
    }
}
