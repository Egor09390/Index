package io.nekohasekai.sagernet.bg.test

import android.os.SystemClock
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.bg.proto.toStartOptions
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.app
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Connection
import okhttp3.Call
import java.io.InterruptedIOException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Measures only one saved profile at a time. The probe box's authenticated loopback inlet has an explicit exit. */
internal class QualityTestRunner(private val session: TestSession) {
    @Volatile private var activeCall: Call? = null

    fun cancel() { activeCall?.cancel() }

    suspend fun run(profiles: List<ProxyEntity>) {
        for (profile in profiles) {
            if (session.cancelled) break
            delay((nextProfileAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (session.cancelled) break
            val network = SagerNet.underlyingNetwork
            val environment = QualityEnvironment.describe(app)
            val report = try {
                val measured = measure(profile)
                if (network != SagerNet.underlyingNetwork) QualityReport(null, "Во время замера сменилась сеть")
                else measured
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                QualityReport(null, "Не удалось запустить проверку профиля")
            } finally {
                // TestEngine holds its process-wide session mutex, including across successive sweeps.
                nextProfileAt = SystemClock.elapsedRealtime() + 250
            }
            if (!session.cancelled) session.reportQuality(profile, report.copy(details = report.details + "\n" + environment))
        }
    }

    private suspend fun measure(profile: ProxyEntity): QualityReport {
        val ports = PortLease()
        try {
            val generated = CoreConfigs.buildTest(listOf(profile.id), ports)
            if (!generated.ok || generated.skipped.containsKey(profile.id)) return QualityReport(null, "Профиль не подготовлен к замеру")
            session.ensureSharedXrayAssets(generated.xrayConfig)
            for (config in generated.xrayFullConfigs) session.ensureSharedXrayAssets(config)
            if (session.cancelled) return QualityReport(null, "Профиль не подготовлен к замеру")

            val fullConfig = generated.fullConfigs[profile.id]
            val core = if (fullConfig == null) CoreConfig.from(generated) else CoreConfig(fullConfig)
            val config = JsonInput.parseObject(core.coreConfig)
            val route = config["route"] as? JsonObject ?: JsonObject().also { config["route"] = it }
            route["auto_detect_interface"] = true
            val exit = generated.outboundTags.singleOrNull() ?: defaultExit(config, route) ?: return QualityReport(null, "Профиль не подготовлен к замеру")

            val port = ports.reserve(1).single()
            if (port == 0) return QualityReport(null, "Профиль не подготовлен к замеру")
            val password = UUID.randomUUID().toString()
            val inbounds = config.array("inbounds")
            inbounds.add(
                jsonObjectOf(
                    "type" to "http", "tag" to PROBE_INBOUND, "listen" to LOOPBACK, "listen_port" to port,
                    "users" to JsonArray().add(jsonObjectOf("username" to PROBE_USER, "password" to password)),
                ),
            )
            config["inbounds"] = inbounds
            val rules = JsonArray().add(jsonObjectOf("inbound" to PROBE_INBOUND, "action" to "route", "outbound" to exit))
            for (rule in route.array("rules")) rules.add(rule)
            route["rules"] = rules

            val start = SystemClock.elapsedRealtimeNanos()
            val instance = Mobile.newInstance(CoreRuntime.platform, core.copy(coreConfig = config.toCompact()).toStartOptions())
            try {
                instance.start()
                return probe(port, password, start)
            } finally {
                runCatching { instance.close() }.onFailure { Logs.w(it) }
            }
        } finally {
            ports.close()
        }
    }

    private fun defaultExit(config: JsonObject, route: JsonObject): String? =
        route.string("final").takeIf { it.isNotBlank() }
            ?: (config.array("outbounds").firstOrNull() as? JsonObject)?.string("tag")?.takeIf { it.isNotBlank() }
            ?: (config.array("endpoints").firstOrNull() as? JsonObject)?.string("tag")?.takeIf { it.isNotBlank() }

    private suspend fun probe(port: Int, password: String, start: Long): QualityReport {
        val credential = Credentials.basic(PROBE_USER, password)
        var connection: Connection? = null
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
            .addNetworkInterceptor { chain ->
                connection = chain.connection()
                chain.proceed(chain.request())
            }
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        val deadline = start + 3_500 * NS_PER_MS
        val baseline = Request.Builder().url(BASELINE).header("Cache-Control", "no-cache").build()
        var previous: Connection? = null

        fun baselineProbe(startTime: Long, timeout: Long): QualityProbe {
            return try {
                call(client, baseline, deadline, timeout) { response ->
                    val ms = (SystemClock.elapsedRealtimeNanos() - startTime) / 1e6
                    val reused = previous != null && previous === connection
                    previous = connection
                    if (response.code == 204) QualityProbe(ms, reused)
                    else QualityProbe(incomplete = true, reason = "HTTP ${response.code}")
                }
            } catch (_: BudgetExpired) {
                QualityProbe(incomplete = true, reason = "лимит времени")
            } catch (_: InterruptedIOException) {
                QualityProbe(timeout = true, reason = "таймаут")
            } catch (_: IOException) {
                QualityProbe(reason = "сбой")
            }
        }

        try {
            val coldBudget = 2000L - (SystemClock.elapsedRealtimeNanos() - start) / NS_PER_MS
            val cold = if (coldBudget > 0) baselineProbe(start, coldBudget)
                else QualityProbe(incomplete = true, reason = "долгий старт ядра")
            val burst = mutableListOf<QualityProbe>()
            repeat(5) { index ->
                if (index > 0) delay(((deadline - SystemClock.elapsedRealtimeNanos()) / NS_PER_MS).coerceIn(0, 80))
                burst += baselineProbe(SystemClock.elapsedRealtimeNanos(), 1500)
            }
            val stream = streamProbe(client, deadline)
            val gateway = gatewayProbe(client, deadline)
            val report = ConnectionQuality.evaluate(cold, burst, stream, gateway)
            return report.copy(details = report.details + "\nЗамер · ${(SystemClock.elapsedRealtimeNanos() - start) / NS_PER_MS} мс")
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private class BudgetExpired : IOException()

    private fun <T> call(client: OkHttpClient, request: Request, deadline: Long, timeoutMs: Long, read: (Response) -> T): T {
        val remaining = deadline - SystemClock.elapsedRealtimeNanos()
        if (remaining <= 0) throw BudgetExpired()
        val limit = minOf(remaining, timeoutMs * NS_PER_MS)
        val call = client.newCall(request)
        call.timeout().timeout(limit, TimeUnit.NANOSECONDS)
        activeCall = call
        try {
            if (session.cancelled) throw CancellationException()
            return call.execute().use(read)
        } catch (e: IOException) {
            if (session.cancelled) throw CancellationException()
            if (limit == remaining && (SystemClock.elapsedRealtimeNanos() >= deadline || e is InterruptedIOException)) {
                throw BudgetExpired()
            }
            throw e
        } finally {
            activeCall = null
        }
    }

    private fun streamProbe(client: OkHttpClient, deadline: Long): StreamProbe {
        val request = Request.Builder().url(STREAM).header("Accept-Encoding", "identity").build()
        var received = 0
        var reached = false
        return try {
            call(client, request, deadline, 1500) { response ->
                reached = true
                val body = response.body
                if (response.code != 200 || body == null) return@call StreamProbe("HTTP ${response.code}", null, true)
                if (response.header("Content-Type").orEmpty().startsWith("text/html")) {
                    return@call StreamProbe("вместо блока получена HTML-страница", null, true)
                }
                val buffer = ByteArray(8192)
                val input = body.byteStream()
                var previous = SystemClock.elapsedRealtimeNanos()
                var maxGap = 0L
                while (received < STREAM_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, STREAM_BYTES - received))
                    if (count < 0) return@call StreamProbe("обрыв · $received Б", 15, true)
                    val now = SystemClock.elapsedRealtimeNanos()
                    maxGap = maxOf(maxGap, now - previous)
                    previous = now
                    received += count
                }
                StreamProbe.complete(maxGap / 1e6)
            }
        } catch (_: BudgetExpired) {
            StreamProbe("лимит времени · $received Б", null, reached)
        } catch (_: IOException) {
            StreamProbe("сбой потока · $received Б", 15, reached)
        }
    }

    private fun gatewayProbe(client: OkHttpClient, deadline: Long): GatewayProbe {
        val request = Request.Builder().url(DataStore.qualityGatewayUrl).head().build()
        return try {
            call(client, request, deadline, 1000) {
                GatewayProbe("HTTP ${it.code}", it.code == 403 || it.code == 503, true)
            }
        } catch (_: BudgetExpired) {
            GatewayProbe("не измерен · лимит времени")
        } catch (_: InterruptedIOException) {
            GatewayProbe("таймаут", restricted = true)
        } catch (_: IOException) {
            GatewayProbe("ошибка соединения")
        }
    }

    private companion object {
        var nextProfileAt = 0L
        const val NS_PER_MS = 1_000_000L
        const val LOOPBACK = "127.0.0.1"
        const val PROBE_USER = "quality"
        const val PROBE_INBOUND = "throne-quality-probe"
        const val BASELINE = "https://cp.cloudflare.com/generate_204"
        const val STREAM_BYTES = 65536
        const val STREAM = "https://speed.cloudflare.com/__down?bytes=65536"
    }
}
