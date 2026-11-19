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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Measures only one saved profile at a time. The probe box's authenticated loopback inlet has an explicit exit. */
internal class QualityTestRunner(private val session: TestSession) {
    suspend fun run(profiles: List<ProxyEntity>) {
        for (profile in profiles) {
            if (session.cancelled) break
            val score = try {
                measure(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                // The profile could not produce a usable test connection.
                1
            }
            if (!session.cancelled) session.reportQuality(profile, score)
        }
    }

    private suspend fun measure(profile: ProxyEntity): Int {
        val ports = PortLease()
        try {
            val generated = CoreConfigs.buildTest(listOf(profile.id), ports)
            if (!generated.ok || generated.skipped.containsKey(profile.id)) return 1
            session.ensureSharedXrayAssets(generated.xrayConfig)
            for (config in generated.xrayFullConfigs) session.ensureSharedXrayAssets(config)
            if (session.cancelled) return 1

            val fullConfig = generated.fullConfigs[profile.id]
            val core = if (fullConfig == null) CoreConfig.from(generated) else CoreConfig(fullConfig)
            val config = JsonInput.parseObject(core.coreConfig)
            val route = config["route"] as? JsonObject ?: JsonObject().also { config["route"] = it }
            val exit = generated.outboundTags.singleOrNull() ?: defaultExit(config, route) ?: return 1

            val port = ports.reserve(1).single()
            if (port == 0) return 1
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

            val instance = Mobile.newInstance(CoreRuntime.platform, core.copy(coreConfig = config.toCompact()).toStartOptions())
            try {
                instance.start()
                return probe(port, password)
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

    private suspend fun probe(port: Int, password: String): Int {
        val credential = Credentials.basic(PROBE_USER, password)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        try {
            val https = HTTPS_SITES.map { url -> List(3) { if (session.cancelled) null else httpsTime(client, url) } }
            val ws = !session.cancelled && webSocketEcho(client)
            val sse = !session.cancelled && eventStream(client)
            return ConnectionQuality.score(https, listOf(ws, sse))
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun httpsTime(client: OkHttpClient, url: String): Long? = try {
        val start = SystemClock.elapsedRealtime()
        client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use {
            if (it.code == 204) SystemClock.elapsedRealtime() - start else null
        }
    } catch (_: IOException) {
        null
    }

    private suspend fun webSocketEcho(client: OkHttpClient): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val challenge = UUID.randomUUID().toString()
        val socket = client.newWebSocket(Request.Builder().url("wss://echo.websocket.org").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(challenge)) answer.complete(false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == challenge) answer.complete(true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                answer.complete(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.complete(false)
            }
        })
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() } == true
        } finally {
            socket.cancel()
        }
    }

    private fun eventStream(client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder().url("https://echo.websocket.org/.sse")
                .header("Accept", "text/event-stream").build()
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return false
                if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                    return false
                }
                val source = body.source()
                var hasData = false
                repeat(24) {
                    val line = source.readUtf8LineStrict(4096)
                    if (line.isEmpty() && hasData) return true
                    if (line.startsWith("data:") && line.substringAfter(':').isNotBlank()) hasData = true
                }
                false
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LOOPBACK = "127.0.0.1"
        const val PROBE_USER = "quality"
        const val PROBE_INBOUND = "throne-quality-probe"
        val HTTPS_SITES = listOf("https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204")
    }
}
