package io.nekohasekai.sagernet.bg.test

import org.junit.Assert.*
import org.junit.Test

class ConnectionQualityTest {
    private val cold = QualityProbe(145.0)
    private val burst = List(5) { QualityProbe(80.0, reused = true) }
    private val stream = StreamProbe.complete(40.0)
    private val gateway = GatewayProbe("HTTP 200", reached = true)

    @Test fun plateauAndIndependentGateway() {
        for (code in listOf(403, 503)) {
            val report = ConnectionQuality.evaluate(cold, burst, stream, GatewayProbe("HTTP $code", true, true))
            assertEquals(100, report.score)
            assertTrue(report.gatewayWarning)
        }
        assertEquals(100, ConnectionQuality.evaluate(cold, burst, stream, GatewayProbe("лимит времени")).score)
    }

    @Test fun lossJitterAndStreamStallAffectSeparateComponents() {
        val loss = burst.toMutableList().apply { this[2] = QualityProbe(timeout = true) }
        assertEquals(85, ConnectionQuality.evaluate(cold, loss, stream, gateway).score)
        loss[1] = QualityProbe(timeout = true)
        assertEquals(73, ConnectionQuality.evaluate(cold, loss, stream, gateway).score)
        val unstable = listOf(60, 100, 60, 100, 60).map { QualityProbe(it.toDouble(), true) }
        assertEquals(70, ConnectionQuality.evaluate(cold, unstable, stream, gateway).score)
        assertEquals(100, ConnectionQuality.evaluate(cold, burst, StreamProbe.complete(500.0), gateway).score)
        val stalled = ConnectionQuality.evaluate(cold, burst, StreamProbe.complete(1400.0), GatewayProbe("HTTP 403", true, true))
        assertEquals(85, stalled.score)
        assertFalse(stalled.gatewayWarning)
    }

    @Test fun reconnectAndDeadlineAreNotWarmSamplesOrLoss() {
        val replaced = burst.toMutableList().apply { this[2] = QualityProbe(900.0) }
        assertEquals(100, ConnectionQuality.evaluate(cold, replaced, stream, gateway).score)
        val unknown = burst.toMutableList().apply { this[4] = QualityProbe(incomplete = true) }
        assertNull(ConnectionQuality.evaluate(cold, unknown, stream, gateway).score)
        assertNull(ConnectionQuality.evaluate(cold, burst, StreamProbe("лимит", null), gateway).score)
        assertNull(ConnectionQuality.evaluate(cold, List(5) { QualityProbe(80.0) }, stream, gateway).score)
    }

    @Test fun allTimedOutAndContradictoryReachability() {
        val timeout = QualityProbe(timeout = true)
        val failed = StreamProbe("обрыв", 15)
        assertEquals(0, ConnectionQuality.evaluate(timeout, List(5) { timeout }, failed, GatewayProbe("таймаут")).score)
        assertEquals(1, ConnectionQuality.evaluate(timeout, List(5) { timeout }, failed, gateway).score)
    }

    @Test fun latencyAndHandshakeBoundaries() {
        fun score(ms: Double, start: Double) = ConnectionQuality.evaluate(
            QualityProbe(start), List(5) { QualityProbe(ms, true) }, stream, gateway).score
        assertEquals(100, score(85.0, 160.0))
        assertEquals(65, score(350.0, 2100.0))
        assertEquals(85, score(80.0, 480.0))
        assertEquals(100, score(10.0, 160.0))
    }
}
