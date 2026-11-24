package io.nekohasekai.sagernet.bg.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QualitySamplerTest {
    @Test fun freshTabKeepAliveFanOutAndRestrictedGateway() = runBlocking(Dispatchers.IO) {
        checkTraffic(85, stalled = false)
    }

    @Test fun interChunkStallIsReported() = runBlocking(Dispatchers.IO) {
        checkTraffic(440, stalled = true)
    }

    private suspend fun checkTraffic(chunkDelay: Long, stalled: Boolean) {
        val visits = Collections.synchronizedList(mutableListOf<Int>())
        val tabs = Collections.synchronizedList(mutableListOf<Int>())
        val streams = CountDownLatch(3)
        val gateway = CountDownLatch(1)
        val loadedPrecededGateway = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (URI(request.requestLine.split(" ")[1]).path) {
                    "/visit" -> {
                        visits.add(request.sequenceNumber)
                        if (visits.size == 8 && streams.count == 0L && gateway.count == 1L) loadedPrecededGateway.countDown()
                        MockResponse().setResponseCode(204)
                    }
                    "/tab" -> {
                        tabs.add(request.sequenceNumber)
                        MockResponse().setResponseCode(204)
                    }
                    "/stream" -> {
                        streams.countDown()
                        check(streams.await(2, TimeUnit.SECONDS))
                        MockResponse().setBody(Buffer().write(ByteArray(65536)))
                            .throttleBody(if (stalled) 32768 else 8192, chunkDelay, TimeUnit.MILLISECONDS)
                    }
                    "/gateway" -> {
                        gateway.countDown()
                        MockResponse().setResponseCode(429)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val result = QualitySampler(
                server.port, "quality", "test",
                gatewayUrl = "http://test.invalid/gateway",
                cloudflareUrl = "http://test.invalid/visit",
                tabUrl = "http://test.invalid/tab",
                downloadUrl = "http://test.invalid/stream",
            ).measure()
            assertEquals((0..7).toList(), visits)
            assertEquals(listOf(0), tabs)
            assertEquals(0L, loadedPrecededGateway.count)
            assertEquals(0L, gateway.count)
            assertNotNull(result.score)
            assertTrue(result.gatewayWarning)
            val streamStatus = if (stalled) "STALL (−25)" else "OK"
            assertTrue(result.details, result.details.contains("Stream: $streamStatus · 192/192 КиБ"))
        }
    }
}
