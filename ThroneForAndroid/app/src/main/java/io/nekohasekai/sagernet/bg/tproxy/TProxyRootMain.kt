package io.nekohasekai.sagernet.bg.tproxy

import android.os.Process
import androidx.annotation.Keep
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.ConnectionOwner
import io.throneproj.mobile.InterfaceUpdateListener
import io.throneproj.mobile.LocalDNSTransport
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.NetworkInterfaceIterator
import io.throneproj.mobile.Notification
import io.throneproj.mobile.PlatformInterface
import io.throneproj.mobile.StartOptions
import io.throneproj.mobile.StringIterator
import io.throneproj.mobile.TunOptions
import io.throneproj.mobile.WIFIState
import kotlin.system.exitProcess

/** Runs from the installed APK under su. The app keeps stdin open for the lifetime of the session. */
@Keep
internal object TProxyRootMain {
    const val READY = "THRONE_TPROXY_READY"
    const val ERROR = "THRONE_TPROXY_ERROR"
    const val ROOT_DENIED = "root-denied"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 0) { ROOT_DENIED }
            val input = System.`in`.bufferedReader()
            val options = input.readLine()?.let(JsonInput::parseObject) ?: return
            val uid = options.int("uid")
            val port = options.int("port")
            val target = options.obj("target")
            check(uid > 0 && port in 1024..65535 && target.int("server_port") in 1..65535)
            check(target.string("server") == "127.0.0.1")
            target["type"] = "socks"
            target["tag"] = "throne"
            target["version"] = "5"

            val inbounds = JsonArray()
            for ((name, address) in listOf("ipv4" to "127.0.0.1", "ipv6" to "::1")) {
                inbounds.add(jsonObjectOf("type" to "tproxy", "tag" to name, "listen" to address, "listen_port" to port))
            }
            val rejectLoopback = jsonObjectOf(
                "ip_cidr" to JsonArray.of("127.0.0.0/8", "::1/128", "::ffff:127.0.0.0/104"),
                "action" to "reject",
            )
            val config = jsonObjectOf(
                "log" to jsonObjectOf("disabled" to true),
                "certificate" to jsonObjectOf("store" to "none"),
                "inbounds" to inbounds,
                "outbounds" to JsonArray.of(target),
                "route" to jsonObjectOf("rules" to JsonArray.of(rejectLoopback), "final" to "throne"),
            )
            val box = Mobile.newInstance(Platform, StartOptions().apply { coreConfig = config.toCompact() })
            try {
                box.start()
                val routes = TProxyRoutes(uid, port, options.array("bypass_ranges").strings(), ::run)
                val cleanup = Thread { runCatching { routes.close() } }
                Runtime.getRuntime().addShutdownHook(cleanup)
                try {
                    routes.start()
                    println(READY)
                    // One control pipe closed on Stop, service death, force-stop or a switch.
                    input.read()
                } finally {
                    routes.close()
                    Runtime.getRuntime().removeShutdownHook(cleanup)
                }
            } finally {
                box.close()
            }
        } catch (error: Throwable) {
            // Do not print the session config or the mixed inbound's credentials.
            val message = (error.message ?: error.javaClass.simpleName).lineSequence().first().take(400)
            println("$ERROR $message")
            exitProcess(1)
        }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() != 0) {
            val name = command.first().substringAfterLast('/')
            error("$name: ${output.ifBlank { "command failed" }}")
        }
    }

    // The root core only listens on loopback and dials a numeric loopback address in the unprivileged core.
    private object Platform : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun usePlatformAutoDetectInterfaceControl() = false
        override fun autoDetectInterfaceControl(fd: Int) = Unit
        override fun openTun(options: TunOptions): Int = error("No TUN in TPROXY")
        override fun useProcFS() = false
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner =
            error("Connection lookup unavailable")
        override fun packageNamesByUid(uid: Int): StringIterator? = null
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
        override fun getInterfaces(): NetworkInterfaceIterator? = null
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() = Unit
        override fun sendNotification(notification: Notification) = Unit
        override fun cancelNotification(identifier: String, typeID: Int) = Unit
    }
}
