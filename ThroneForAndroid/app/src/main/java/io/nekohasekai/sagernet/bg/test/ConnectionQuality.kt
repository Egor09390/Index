package io.nekohasekai.sagernet.bg.test

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class ProbeState { OK, LOST, SKIPPED }

internal data class QualityTiming(
    val state: ProbeState,
    val millis: Double? = null,
) {
    companion object {
        val lost = QualityTiming(ProbeState.LOST)
        val skipped = QualityTiming(ProbeState.SKIPPED)
        fun ok(millis: Double?) = QualityTiming(ProbeState.OK, millis)
    }
}

internal data class DownloadProbe(val state: ProbeState, val bytes: Int, val stalled: Boolean)
internal data class GatewayProbe(val status: String, val restricted: Boolean = false)
internal data class QualityReport(val score: Int?, val details: String, val gatewayWarning: Boolean = false)

internal object ConnectionQuality {
    const val IDLE_REQUESTS = 6
    const val DOWNLOADS = 3
    const val STREAM_BYTES = 64 * 1024
    private const val CONTROL_REQUESTS = IDLE_REQUESTS + 3

    fun evaluate(
        cold: QualityTiming,
        tab: QualityTiming,
        idle: List<QualityTiming>,
        loaded: QualityTiming,
        downloads: List<DownloadProbe>,
        gateway: GatewayProbe,
    ): QualityReport {
        require(idle.size == IDLE_REQUESTS && downloads.size == DOWNLOADS)
        val controls = listOf(cold, tab, loaded) + idle
        val losses = controls.count { it.state == ProbeState.LOST }
        val failedDownloads = downloads.count { it.state == ProbeState.LOST }
        val loss = losses + 2 * failedDownloads
        val lossPoints = when (loss) {
            0 -> 35
            1 -> 15
            2 -> 4
            else -> 0
        }

        val successful = idle.mapNotNull { it.millis }
        val sorted = successful.sorted()
        val floor = sorted.firstOrNull()
        val median = if (sorted.isEmpty()) null else (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val idleBloat = if (median != null && floor != null) median - floor else null
        val deltas = successful.zipWithNext { a, b -> abs(a - b) }.sorted()
        val cleanDeltas = if (deltas.size >= 3) deltas.dropLast(1) else deltas
        val jitter = cleanDeltas.takeIf { it.isNotEmpty() }?.average()
        val loadRatio = median?.let { m -> loaded.millis?.div(m.coerceAtLeast(20.0)) }
        val loadDelta = median?.let { m -> loaded.millis?.minus(m)?.coerceAtLeast(0.0) }
        val base = floor?.coerceAtLeast(20.0)
        val tabRatio = base?.let { tab.millis?.div(it) }
        val coldRatio = base?.let { cold.millis?.div(it) }

        val loadedPoints = when {
            loadRatio == null || loadDelta == null -> 0.0
            loadDelta <= 28.0 -> 18.0
            else -> linear(loadRatio, 1.35, 3.6, 18.0)
        }
        val rpmPoints = loadedPoints + linear(idleBloat, 4.5, 35.0, 7.0)
        val jitterPoints = jitter?.let { 20.0 * (1.0 - ((it - 5.5) / 32.5).coerceIn(0.0, 1.0).pow(0.85)) } ?: 0.0
        val tabPoints = if (floor != null && tab.millis != null && tab.millis - floor <= 35.0) 13.0
            else linear(tabRatio, 1.35, 3.2, 13.0)
        val coldPoints = if (cold.millis != null && cold.millis <= 180.0) 7.0
            else linear(coldRatio, 2.4, 5.5, 7.0)
        val navPoints = tabPoints + coldPoints
        val routePenalty = floor?.let { ((it - 205.0) * 0.14).coerceIn(0.0, 25.0) } ?: 0.0
        val stalled = downloads.any { it.stalled }
        val stallPenalty = if (stalled) 25 else 0
        val score = when {
            losses == CONTROL_REQUESTS -> 0
            controls.any { it.state == ProbeState.SKIPPED } || downloads.any { it.state == ProbeState.SKIPPED } -> null
            else -> (lossPoints + rpmPoints + jitterPoints + navPoints - stallPenalty - routePenalty)
                .roundToInt().coerceIn(1, 100)
        }
        val streamStatus = when {
            stalled -> "STALL (−25)"
            failedDownloads > 0 -> "Сбой · $failedDownloads/3"
            downloads.all { it.state == ProbeState.OK } -> "OK"
            else -> "не измерен"
        }
        val details = listOf(
            "Floor: ${number(floor)} мс | Idle: ${number(median)} мс (+${number(idleBloat)} мс)",
            "Loaded (192KB): ${number(loaded.millis)} мс (${number(loadRatio)}x) [${rpmPoints.roundToInt()}/25]",
            "Tab: ${number(tab.millis)} мс (${number(tabRatio)}x) | Cold: ${number(cold.millis)} мс [${navPoints.roundToInt()}/20]",
            "Jitter: ${number(jitter)} мс [${jitterPoints.roundToInt()}/20] | Loss: $loss/9 [$lossPoints/35]",
            "Stream: $streamStatus · ${downloads.sumOf { it.bytes } / 1024}/192 КиБ | Gateway: ${gateway.status}",
        ) + listOfNotNull(
            "Замер неполный".takeIf { score == null },
            "Штраф за маршрут: −${number(routePenalty)}".takeIf { routePenalty > 0 },
        )
        return QualityReport(score, details.joinToString("\n"), gateway.restricted)
    }

    private fun linear(value: Double?, best: Double, worst: Double, points: Double): Double =
        value?.let { points * ((worst - it) / (worst - best)).coerceIn(0.0, 1.0) } ?: 0.0

    private fun number(value: Double?): String =
        value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
}
