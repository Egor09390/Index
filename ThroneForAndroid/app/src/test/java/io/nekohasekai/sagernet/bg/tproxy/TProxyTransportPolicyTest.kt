package io.nekohasekai.sagernet.bg.tproxy

import io.nekohasekai.sagernet.outbound.json.JsonInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TProxyTransportPolicyTest {
    @Test
    fun onlyTcpOnlyNaiveFallsBackWhenThereIsNoAlternateUdpRoute() {
        fun fallback(type: String, extra: String = "", rules: String = "[]", final: String = "proxy"): Boolean =
            requiresQuicFallback(JsonInput.parseObject("""
                {"outbounds":[{"type":"$type","tag":"proxy"$extra}],
                 "route":{"final":"$final","rules":$rules}}
            """.trimIndent()))
        assertTrue(fallback("naive"))
        assertTrue(fallback("naive", rules = """[{"network":"tcp","outbound":"direct"}]"""))
        assertTrue(fallback("naive", rules = """[{"inbound":"internal","outbound":"direct"}]"""))
        assertTrue(fallback("naive", rules = """[{"port":[53],"outbound":"direct"}]"""))
        assertFalse(fallback("naive", extra = ""","udp_over_tcp":true"""))
        assertFalse(fallback("naive", extra = ""","udp_over_tcp":{"enabled":true}"""))
        assertFalse(fallback("tuic"))
        assertFalse(fallback("naive", rules = """[{"network":["udp"],"outbound":"direct"}]"""))
        assertFalse(fallback("naive", final = "direct"))
    }
}
