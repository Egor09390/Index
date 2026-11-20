package io.nekohasekai.sagernet.bg.tproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TProxyRoutesTest {
    @Test
    fun stopReversesPolicyAndFirewallHooks() {
        val commands = mutableListOf<List<String>>()
        val routes = TProxyRoutes(10231, 30080, listOf("10.0.0.0/8", "fc00::/7"), emptySet(), commands::add)
        routes.start()
        val beforeClose = commands.size
        assertEquals(4, commands.count { "TPROXY" in it && "--on-port" in it })
        assertEquals(2, commands.count { "--uid-owner" in it && "10231" in it })

        routes.close()

        val cleanup = commands.drop(beforeClose)
        assertTrue(cleanup.take(2).all { "-D" in it && "OUTPUT" in it })
        assertTrue(cleanup.drop(2).take(2).all { "-D" in it && "PREROUTING" in it })
        assertEquals(2, cleanup.count { "route" in it && "del" in it })
        assertEquals(2, cleanup.count { "rule" in it && "del" in it })
        assertEquals(4, cleanup.count { "-X" in it })
        val cleanupSize = commands.size
        routes.close()
        assertEquals(cleanupSize, commands.size)
    }

    @Test
    fun preroutingSkipsRepliesBeforeApplyingTproxy() {
        val commands = mutableListOf<List<String>>()
        TProxyRoutes(10231, 30080, emptyList(), emptySet(), commands::add).start()

        for (name in listOf("iptables", "ip6tables")) {
            val rules = commands.filter {
                it.first().endsWith("/$name") && "-A" in it && "THRONE_TPROXY_IN" in it
            }
            assertEquals(3, rules.size)
            assertTrue(rules.first().takeLast(4) == listOf("--ctdir", "REPLY", "-j", "RETURN"))
            assertTrue(rules.drop(1).all { "TPROXY" in it })
        }
    }

    @Test
    fun androidVpnTransportBypassesInterceptionIncludingDns() {
        val commands = mutableListOf<List<String>>()
        TProxyRoutes(10231, 30080, emptyList(), emptySet(), commands::add).start()

        for (name in listOf("iptables", "ip6tables")) {
            val rules = commands.filter {
                it.first().endsWith("/$name") && "-A" in it && "THRONE_TPROXY_OUT" in it
            }
            val exclusion = rules.indexOfFirst {
                it.takeLast(6) == listOf("-m", "mark", "--mark", "0x00020000/0x00020000", "-j", "RETURN")
            }
            assertTrue(exclusion >= 0)
            assertTrue(exclusion < rules.indexOfFirst { "--set-xmark" in it })
        }
    }

    @Test
    fun vpnClientsBypassBeforeDnsAndDataEvenWithoutVpnSocketMarks() {
        val commands = mutableListOf<List<String>>()
        TProxyRoutes(10231, 30080, emptyList(), setOf(10257, 10732), commands::add).start()

        for (name in listOf("iptables", "ip6tables")) {
            val outputRules = commands.filter {
                it.first().endsWith("/$name") && "-A" in it && "THRONE_TPROXY_OUT" in it
            }
            val firstMark = outputRules.indexOfFirst { "--set-xmark" in it }
            assertTrue(firstMark >= 0)
            for (uid in listOf("10257", "10732")) {
                assertEquals(
                    1,
                    outputRules.take(firstMark).count {
                        it.takeLast(6) == listOf("-m", "owner", "--uid-owner", uid, "-j", "RETURN")
                    },
                )
            }
        }
        val firstHook = commands.indexOfFirst { "-I" in it && "OUTPUT" in it }
        assertTrue(firstHook > commands.indexOfLast { "THRONE_TPROXY_OUT" in it && "-A" in it })
    }

    @Test
    fun androidVpnPacketsKeepTheirSystemRouteBeforeDnsAndQuicRules() {
        val commands = mutableListOf<List<String>>()
        TProxyRoutes(10231, 30080, emptyList(), emptySet(), commands::add)
            .start(rejectQuic = true, androidVpnInterfaces = setOf("tun2"))

        for (firewall in listOf("iptables", "ip6tables")) {
            val outputRules = commands.filter {
                it.first().endsWith("/$firewall") && "-A" in it && "THRONE_TPROXY_OUT" in it
            }
            val firstMark = outputRules.indexOfFirst { "--set-xmark" in it }
            assertTrue(firstMark >= 0)
            assertTrue(outputRules.take(firstMark).any {
                it.takeLast(4) == listOf("-o", "tun2", "-j", "RETURN")
            })
        }
    }

    @Test
    fun changingVpnInterfacesUpdatesBothFamiliesAndRestoresInterceptionOnDisconnect() {
        val commands = mutableListOf<List<String>>()
        val routes = TProxyRoutes(10231, 30080, emptyList(), emptySet(), commands::add)
        routes.start()
        val before = commands.size
        assertTrue(routes.setAndroidVpnInterfaces(setOf("tun1")))
        assertTrue(routes.setAndroidVpnInterfaces(setOf("ipsec3")))
        assertTrue(!routes.setAndroidVpnInterfaces(setOf("ipsec3")))
        assertTrue(routes.setAndroidVpnInterfaces(emptySet()))

        val updates = commands.drop(before)
        val steps = updates.map { it[5] to it[it.indexOf("-o") + 1] }
        assertEquals(listOf(
            "-I" to "tun1", "-I" to "tun1", "-I" to "ipsec3", "-I" to "ipsec3",
            "-D" to "tun1", "-D" to "tun1", "-D" to "ipsec3", "-D" to "ipsec3",
        ), steps)
        assertEquals(4, updates.count { it.first().endsWith("/iptables") })
        assertEquals(4, updates.count { it.first().endsWith("/ip6tables") })
        assertTrue(updates.all { "THRONE_TPROXY_OUT" in it && it.takeLast(2) == listOf("-j", "RETURN") })
        routes.close()
    }

    @Test
    fun packetCountsReportOnlyTransparentProxyRules() {
        val routes = TProxyRoutes(10231, 30080, emptyList(), emptySet()) { }
        val stats = routes.packetCounts { command ->
            assertTrue(command.takeLast(5) == listOf("-L", "THRONE_TPROXY_IN", "-n", "-v", "-x"))
            val v4 = command.first().endsWith("/iptables")
            """
            Chain THRONE_TPROXY_IN (1 references)
            pkts bytes target prot opt in out source destination
               99  5550 RETURN all  --  lo * 0.0.0.0/0 0.0.0.0/0
               ${if (v4) 12 else 0}  1024 TPROXY tcp --  lo * 0.0.0.0/0 0.0.0.0/0
               ${if (v4) 3 else 8}  512 TPROXY udp --  lo * 0.0.0.0/0 0.0.0.0/0
            """.trimIndent()
        }
        assertEquals("Packets intercepted by TPROXY: IPv4 tcp=12 udp=3, IPv6 tcp=0 udp=8", stats)
    }

    @Test
    fun quicFallbackRejectsOnlyCapturedUdp443AndCleansUp() {
        val commands = mutableListOf<List<String>>()
        val routes = TProxyRoutes(10231, 30080, emptyList(), setOf(10257), commands::add)
        routes.start(rejectQuic = true)
        val installs = commands.filter { "filter" in it && "-I" in it }
        assertEquals(2, installs.size)
        assertTrue(installs.all { "--mark" in it && "0x10000000/0x10000000" in it && "--dport" in it &&
            "443" in it && "udp" in it && "REJECT" in it })
        assertTrue("icmp-port-unreachable" in installs[0] && "icmp6-port-unreachable" in installs[1])
        val plain = mutableListOf<List<String>>()
        TProxyRoutes(10231, 30080, emptyList(), emptySet(), plain::add).start()
        assertTrue(plain.none { "REJECT" in it })
        routes.close()
        assertEquals(2, commands.count { "filter" in it && "-D" in it && "REJECT" in it })
    }

    @Test
    fun failedSecondOutputHookRemovesFirstOutputHookAndBothPolicyRoutes() {
        val commands = mutableListOf<List<String>>()
        val failure = IllegalStateException("test failure")
        val routes = TProxyRoutes(10231, 30080, emptyList(), emptySet()) { command ->
            commands.add(command)
            if (command.first().endsWith("ip6tables") && "-I" in command && "OUTPUT" in command) throw failure
        }

        assertEquals(failure, runCatching { routes.start() }.exceptionOrNull())
        assertEquals(1, commands.count { "-D" in it && "OUTPUT" in it })
        assertEquals(2, commands.count { "-D" in it && "PREROUTING" in it })
        assertEquals(2, commands.count { "route" in it && "del" in it })
    }
}
