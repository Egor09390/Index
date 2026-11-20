package io.nekohasekai.sagernet.bg.tproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TProxyRoutesTest {
    @Test
    fun stopReversesPolicyAndFirewallHooks() {
        val commands = mutableListOf<List<String>>()
        val routes = TProxyRoutes(10231, 30080, listOf("10.0.0.0/8", "fc00::/7"), commands::add)
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
    fun failedSecondOutputHookRemovesFirstOutputHookAndBothPolicyRoutes() {
        val commands = mutableListOf<List<String>>()
        val failure = IllegalStateException("test failure")
        val routes = TProxyRoutes(10231, 30080, emptyList()) { command ->
            commands.add(command)
            if (command.first().endsWith("ip6tables") && "-I" in command && "OUTPUT" in command) throw failure
        }

        assertEquals(failure, runCatching { routes.start() }.exceptionOrNull())
        assertEquals(1, commands.count { "-D" in it && "OUTPUT" in it })
        assertEquals(2, commands.count { "-D" in it && "PREROUTING" in it })
        assertEquals(2, commands.count { "route" in it && "del" in it })
    }
}
