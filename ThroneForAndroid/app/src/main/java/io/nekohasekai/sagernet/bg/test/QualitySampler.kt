package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One budget and one isolated proxy instance, with separate HTTP pools for the first visit, new tab and downloads. */
internal class QualitySampler(
    port: Int,
    username: String,
    password: String,
    gatewayUrl: String,
    cloudflareUrl: String = "https://cloudflare.com/",
    tabUrl: String = "https://gstatic.com/",
    downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=65536",
) {
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    private val credential = Credentials.basic(username, password)
    private val baseline = head(cloudflareUrl)
    private val tab = head(tabUrl)
    private val gateway = head(gatewayUrl)
    private val download = Request.Builder().url(downloadUrl).header("Accept-Encoding", "identity").build()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false
    private var deadline = 0L

    fun cancel() {
        cancelled = true
        activeCalls.forEach { it.cancel() }
    }

    suspend fun measure(): QualityReport {
        var acquired: Connection? = null
        val visitClient = newClient { acquired = it }
        val tabClient = newClient()
        val downloadClient = newClient()
        val start = System.nanoTime()
        deadline = start + 5200 * NS_PER_MS
        try {
            val cold = latency(visitClient, baseline, 2500)
            var previous = acquired
            val freshTab = latency(tabClient, tab, 1800)
            val idle = ArrayList<QualityTiming>(ConnectionQuality.IDLE_REQUESTS)
            repeat(ConnectionQuality.IDLE_REQUESTS) { index ->
                val probe = if (index > 0 && timeLeft() < 50 * NS_PER_MS) {
                    QualityTiming.skipped
                } else {
                    if (index > 0) delay(50)
                    latency(visitClient, baseline, 1500)
                }
                val reused = previous != null && previous === acquired
                if (probe.state == ProbeState.OK) previous = acquired
                idle += if (probe.state == ProbeState.OK && !reused) QualityTiming.ok(null) else probe
            }

            val load = fanOut(visitClient, downloadClient)
            val gatewayResult = gatewayProbe(tabClient)
            val report = ConnectionQuality.evaluate(cold, freshTab, idle, load.timing, load.downloads, gatewayResult)
            val measurementNotes = buildList {
                if (load.timing.millis != null && !load.activeWhenPinged) add("Потоки завершились до контрольного запроса")
                val newConnections = idle.count { it.state == ProbeState.OK && it.millis == null }
                if (newConnections > 0) add("Новые соединения в серии: $newConnections")
                add("Замер · ${(System.nanoTime() - start) / NS_PER_MS} мс")
            }
            return report.copy(details = report.details + "\n" + measurementNotes.joinToString("\n"))
        } finally {
            for (client in listOf(visitClient, tabClient, downloadClient)) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private data class LoadResult(
        val timing: QualityTiming,
        val downloads: List<DownloadProbe>,
        val activeWhenPinged: Boolean,
    )

    private suspend fun fanOut(visitClient: OkHttpClient, downloadClient: OkHttpClient): LoadResult = coroutineScope {
        val firstData = CompletableDeferred<Boolean>()
        val unfinished = AtomicInteger(ConnectionQuality.DOWNLOADS)
        val streams = List(ConnectionQuality.DOWNLOADS) {
            async(Dispatchers.IO) {
                try {
                    transfer(downloadClient) { firstData.complete(true) }
                } finally {
                    if (unfinished.decrementAndGet() == 0) firstData.complete(false)
                }
            }
        }
        val hasData = firstData.await()
        if (hasData && timeLeft() <= 55 * NS_PER_MS) {
            return@coroutineScope LoadResult(QualityTiming.skipped, streams.awaitAll(), false)
        }
        if (hasData) delay(55)
        val activeWhenPinged = hasData && unfinished.get() > 0
        val probe = latency(visitClient, baseline, 1500)
        val loaded = if (!hasData && probe.state == ProbeState.OK) QualityTiming.ok(null) else probe
        LoadResult(loaded, streams.awaitAll(), activeWhenPinged)
    }

    private fun head(url: String) = Request.Builder().url(url).head().header("Cache-Control", "no-cache").build()

    private fun newClient(acquired: ((Connection?) -> Unit)? = null): OkHttpClient = OkHttpClient.Builder()
        .proxy(proxy)
        .proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder().header("Proxy-Authorization", credential).build()
        }
        .apply {
            if (acquired != null) addNetworkInterceptor { chain ->
                acquired(chain.connection())
                chain.proceed(chain.request())
            }
        }
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    private class BudgetExpired : IOException()

    private fun timeLeft() = deadline - System.nanoTime()

    private fun <T> call(client: OkHttpClient, request: Request, timeout: Long, read: (Response) -> T): T {
        val remaining = timeLeft()
        if (remaining <= 0) throw BudgetExpired()
        val call = client.newCall(request)
        call.timeout().timeout(minOf(remaining, timeout * NS_PER_MS), TimeUnit.NANOSECONDS)
        activeCalls.add(call)
        try {
            if (cancelled) throw CancellationException()
            return call.execute().use(read)
        } catch (e: IOException) {
            if (cancelled) throw CancellationException()
            throw e
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun latency(client: OkHttpClient, request: Request, timeout: Long): QualityTiming {
        val started = System.nanoTime()
        return try {
            call(client, request, timeout) {
                if (it.code in 200..399) QualityTiming.ok((System.nanoTime() - started) / 1e6) else QualityTiming.lost
            }
        } catch (_: BudgetExpired) {
            QualityTiming.skipped
        } catch (_: IOException) {
            QualityTiming.lost
        }
    }

    private fun transfer(client: OkHttpClient, firstData: () -> Unit): DownloadProbe {
        var received = 0
        var lastChunk = 0L
        var stalled = false
        return try {
            call(client, download, 3000) { response ->
                val body = response.body
                if (response.code != 200 || body == null ||
                    response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)
                ) return@call DownloadProbe(ProbeState.LOST, 0, false)
                val input = body.byteStream()
                val buffer = ByteArray(8 * 1024)
                while (received < ConnectionQuality.STREAM_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, ConnectionQuality.STREAM_BYTES - received))
                    val now = System.nanoTime()
                    if (lastChunk != 0L && now - lastChunk > 420 * NS_PER_MS) stalled = true
                    if (count < 0) break
                    lastChunk = now
                    received += count
                    firstData()
                }
                val state = if (received == ConnectionQuality.STREAM_BYTES) ProbeState.OK else ProbeState.LOST
                DownloadProbe(state, received, stalled)
            }
        } catch (_: BudgetExpired) {
            DownloadProbe(ProbeState.SKIPPED, 0, false)
        } catch (_: IOException) {
            DownloadProbe(ProbeState.LOST, received, stalled || (lastChunk != 0L && System.nanoTime() - lastChunk > 420 * NS_PER_MS))
        }
    }

    private fun gatewayProbe(client: OkHttpClient): GatewayProbe = try {
        call(client, gateway, 2000) {
            when (it.code) {
                403, 429, 503 -> GatewayProbe("ASN Throttled · HTTP ${it.code}", restricted = true)
                in 200..399 -> GatewayProbe("OK")
                else -> GatewayProbe("HTTP ${it.code}")
            }
        }
    } catch (_: BudgetExpired) {
        GatewayProbe("не измерен · лимит времени")
    } catch (_: InterruptedIOException) {
        GatewayProbe("ASN Throttled · таймаут", restricted = true)
    } catch (_: IOException) {
        GatewayProbe("ошибка соединения")
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
