package io.nekohasekai.sagernet.bg.test

import kotlin.math.roundToInt

/** HTTPS round trips are grouped by destination so natural differences between sites do not count as jitter. */
internal object ConnectionQuality {
    fun score(https: List<List<Long?>>, streaming: List<Boolean>): Int {
        val attempts = https.flatten()
        val received = attempts.count { it != null }
        if (received == 0 && streaming.none { it }) return 1

        val availability = 60.0 * received / attempts.size
        val streams = 20.0 * streaming.count { it } / streaming.size
        val responsiveness = https.map { samples ->
            val times = samples.filterNotNull().sorted()
            if (times.size < 2) return@map 0.0
            val slowest = times.last()
            val spread = slowest - times.first()
            points(slowest, 150, 1_500) + points(spread, 40, 500)
        }.average()
        return (availability + streams + responsiveness).roundToInt().coerceIn(1, 100)
    }

    private fun points(millis: Long, best: Long, worst: Long): Double =
        10.0 * ((worst - millis).toDouble() / (worst - best)).coerceIn(0.0, 1.0)
}
