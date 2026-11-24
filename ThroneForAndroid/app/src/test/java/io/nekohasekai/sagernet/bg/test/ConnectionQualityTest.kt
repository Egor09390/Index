package io.nekohasekai.sagernet.bg.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class ConnectionQualityTest {
    private val idle = List(6) { QualityTiming.ok(65.0) }
    private val healthy = List(3) { DownloadProbe(ProbeState.OK, 65536, false) }

    private fun score(
        cold: QualityTiming = QualityTiming.ok(130.0),
        tab: QualityTiming = QualityTiming.ok(90.0),
        idle: List<QualityTiming> = this.idle,
        loaded: QualityTiming = QualityTiming.ok(80.0),
        downloads: List<DownloadProbe> = healthy,
        gateway: GatewayProbe = GatewayProbe("OK"),
    ) = ConnectionQuality.evaluate(cold, tab, idle, loaded, downloads, gateway)

    @Test fun scoreCeilingAndGatewayIsolation() {
        assertEquals(100, score().score)
        val report = score(gateway = GatewayProbe("ASN Throttled", true))
        assertEquals(100, report.score)
        assertTrue(report.gatewayWarning)
        assertFalse(score().gatewayWarning)
    }

    @Test fun lossAndStallPenalties() {
        val lostIdle = idle.toMutableList()
        lostIdle[2] = QualityTiming.lost
        assertEquals(80, score(idle = lostIdle).score)
        lostIdle[3] = QualityTiming.lost
        assertEquals(69, score(idle = lostIdle).score)
        lostIdle[4] = QualityTiming.lost
        assertEquals(65, score(idle = lostIdle).score)

        val downloads = healthy.toMutableList()
        downloads[0] = DownloadProbe(ProbeState.OK, 65536, true)
        downloads[1] = DownloadProbe(ProbeState.OK, 65536, true)
        assertEquals(75, score(downloads = downloads).score)
        downloads[1] = DownloadProbe(ProbeState.LOST, 8192, true)
        val report = score(downloads = downloads)
        assertEquals(44, report.score)
        assertTrue(report.details.contains("Loss: 2/9 [4/35]"))
    }

    @Test fun unavailableAndIncompleteAreDistinct() {
        val lost = QualityTiming.lost
        val missed = List(6) { lost }
        assertEquals(0, score(lost, lost, missed, lost).score)
        assertNull(score(lost, lost, missed, QualityTiming.skipped).score)
        assertNull(score(downloads = listOf(healthy[0], healthy[1], DownloadProbe(ProbeState.SKIPPED, 0, false))).score)
        assertEquals(1, score(lost, lost, missed, QualityTiming.ok(null)).score)
    }

    @Test fun allowancesAndLinearWeights() {
        assertEquals(100, score(loaded = QualityTiming.ok(93.0), tab = QualityTiming.ok(100.0)).score)
        val backlog = score(
            idle = listOf(60.0, 66.0, 66.0, 72.0, 72.0, 72.0).map(QualityTiming::ok),
            loaded = QualityTiming.ok(207.0),
            tab = QualityTiming.ok(150.0),
            cold = QualityTiming.ok(240.0),
        )
        val expected = (35 + 18.0 * (1 - (3.0 - 1.35) / 2.25) +
            7.0 * (1 - (9.0 - 4.5) / 30.5) + 20 +
            13.0 * (1 - (2.5 - 1.35) / 1.85) + 7.0 * (1 - (4.0 - 2.4) / 3.1)).roundToInt()
        assertEquals(expected, backlog.score)

        val limited = List(6) { QualityTiming.ok(10.0) }
        assertEquals(100, score(QualityTiming.ok(180.0), QualityTiming.ok(45.0), limited, QualityTiming.ok(38.0)).score)
    }

    @Test fun excludeLargestJitterAndCapRoutePenalty() {
        val jittered = score(idle = listOf(65.0, 70.0, 65.0, 95.0, 65.0, 70.0).map(QualityTiming::ok))
        val clean = (5.0 + 5.0 + 5.0 + 30.0) / 4
        val expected = (80 + 20.0 * (1 - ((clean - 5.5) / 32.5).pow(0.85))).roundToInt()
        assertEquals(expected, jittered.score)
        val longRoute = List(6) { QualityTiming.ok(255.0) }
        assertEquals(93, score(QualityTiming.ok(500.0), QualityTiming.ok(260.0), longRoute, QualityTiming.ok(260.0)).score)
        val farRoute = List(6) { QualityTiming.ok(500.0) }
        assertEquals(75, score(QualityTiming.ok(800.0), QualityTiming.ok(540.0), farRoute, QualityTiming.ok(540.0)).score)
    }
}
