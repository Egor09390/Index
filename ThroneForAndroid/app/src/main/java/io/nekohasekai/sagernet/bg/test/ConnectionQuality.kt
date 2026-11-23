package io.nekohasekai.sagernet.bg.test

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/** A miss is an attempted application probe. A deadline or an invalid baseline yields no comparable result. */
internal data class QualityProbe(
    val millis: Double? = null,
    val reused: Boolean = false,
    val timeout: Boolean = false,
    val incomplete: Boolean = false,
    val reason: String = "",
)

internal data class StreamProbe(val description: String, val penalty: Int?, val reached: Boolean = false) {
    companion object {
        fun complete(maxGapMs: Double): StreamProbe {
            val penalty = ceil((maxGapMs - 500.0).coerceAtLeast(0.0) / 60.0).toInt().coerceAtMost(15)
            return StreamProbe("64 КиБ · пауза до ${maxGapMs.roundToInt()} мс", penalty, true)
        }
    }
}

internal data class GatewayProbe(val description: String, val restricted: Boolean = false, val reached: Boolean = false)
internal data class QualityReport(val score: Int?, val details: String, val gatewayWarning: Boolean = false)

internal object ConnectionQuality {
    private fun number(n: Double?) = n?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"

    fun evaluate(cold: QualityProbe, burst: List<QualityProbe>, stream: StreamProbe, gateway: GatewayProbe): QualityReport {
        require(burst.size == 5)
        val probes = listOf(cold) + burst
        val warm = burst.map { probe -> probe.millis.takeIf { probe.reused } }
        val sorted = warm.filterNotNull().sorted()
        val median = if (sorted.isEmpty()) null else (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val differences = warm.zipWithNext().mapNotNull { (a, b) -> if (a == null || b == null) null else abs(a - b) }
        val jitter = differences.takeIf { it.isNotEmpty() }?.average()
        val lost = probes.count { it.millis == null && !it.incomplete }
        val lossPoints = when (lost) { 0 -> 35; 1 -> 20; 2 -> 8; else -> 0 }
        val jitterPoints = when {
            jitter == null || jitter >= 40 -> 0.0
            jitter <= 4 -> 30.0
            else -> 30 * (1 - ((jitter - 4) / 36).pow(0.85))
        }
        val latencyPoints = median?.let { 20 * ((350 - it) / 265).coerceIn(0.0, 1.0) } ?: 0.0
        val ratio = median?.let { cold.millis?.div(it.coerceAtLeast(15.0)) }
        val handshakePoints = when {
            cold.millis == null -> 0.0
            cold.millis <= 160 -> 15.0
            ratio == null -> 0.0
            else -> 15 * ((6 - ratio) / 4.2).coerceIn(0.0, 1.0)
        }
        val allTimedOut = probes.all { it.timeout } && !stream.reached && !gateway.reached
        val score = when {
            allTimedOut -> 0
            probes.any { it.incomplete } || stream.penalty == null -> null
            sorted.isEmpty() && lost < 6 -> null
            else -> (lossPoints + jitterPoints + latencyPoints + handshakePoints - stream.penalty).roundToInt().coerceIn(1, 100)
        }
        val warning = lost == 0 && probes.none { it.incomplete } && stream.penalty == 0 && gateway.restricted
        val finished = probes.count { !it.incomplete }
        val details = listOf(
            "Прогретая медиана · ${number(median)} мс  (${number(latencyPoints)}/20)",
            "Джиттер · ${number(jitter)} мс  (${number(jitterPoints)}/30)",
            "Холодный старт · ${number(cold.millis)} мс  (${number(handshakePoints)}/15)",
            "Неудачные пробы · $lost/$finished  (" + (if (finished == 6) "$lossPoints/35" else "замер неполный") + ")",
            "Ответы · " + burst.joinToString(" / ") {
                when {
                    it.millis == null -> it.reason
                    it.reused -> number(it.millis)
                    else -> "${number(it.millis)} (новое соединение)"
                }
            },
            "Поток · ${stream.description}" + (stream.penalty?.let { "  (−$it)" } ?: ""),
            "Шлюз · ${gateway.description}" + (if (warning) " · возможное ограничение выхода" else ""),
        ).joinToString("\n")
        return QualityReport(score, details, warning)
    }
}
