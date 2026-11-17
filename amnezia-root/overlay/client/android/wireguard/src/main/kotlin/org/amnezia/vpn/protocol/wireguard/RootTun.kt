package org.amnezia.vpn.protocol.wireguard

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root process creates an actual kernel TUN device, passing only its FD to wg-go.
 * The app process and its WireGuard Go backend never run as root.
 *
 * The two assets are installed by the fork's Android build job for arm64-v8a.
 */
internal object RootTun {
    private const val TUN_ASSET = "amnezia-root-tun-arm64"
    private const val ROUTE_ASSET = "amnezia-root-routing.sh"
    private val assets = listOf(TUN_ASSET, ROUTE_ASSET)

    private fun uid(context: Context): Int = context.applicationInfo.uid
    private fun installed(name: String, context: Context): String =
        "/data/local/tmp/amnezia-" + uid(context) + "-" + name

    private fun root(command: String, timeout: Long = 15): String {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                error("Root command timed out")
            }
            val output = p.inputStream.bufferedReader().readText()
            if (p.exitValue() != 0) error("Root command failed (" + p.exitValue() + "): " + output.take(700))
            return output
        } finally {
            p.destroy()
        }
    }

    private fun ensureInstalled(context: Context) {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Root AWG preview is arm64-v8a only" }
        val local = File(context.filesDir, "amnezia-root")
        if (!local.exists()) check(local.mkdirs())
        for (name in assets) {
            val file = File(local, name)
            context.assets.open(name).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val dest = installed(name, context)
            // Only fixed application-private paths and fixed asset names reach the shell.
            root("cp '" + file.absolutePath + "' '" + dest + "' && chmod 700 '" + dest + "'")
        }
    }

    fun prepare(context: Context) = ensureInstalled(context)

    fun cleanup(context: Context) {
        val script = installed(ROUTE_ASSET, context)
        // The script may not exist yet, e.g. after application data was cleared.
        try {
            root("test ! -f '" + script + "' || sh '" + script + "' stop " + uid(context))
        } catch (_: Exception) {
            // Shutdown is best-effort; connect validates a clean state before routing.
        }
    }

    fun refresh(context: Context) {
        val script = installed(ROUTE_ASSET, context)
        root("sh '" + script + "' refresh " + uid(context))
    }

    fun installRouting(context: Context, clientIpv4: String, dnsIpv4: String) {
        // No untrusted values are permitted to reach su's shell.
        require(clientIpv4.matches(Regex("[0-9./]+"))) { "Expected IPv4 CIDR" }
        require(dnsIpv4.isEmpty() || dnsIpv4.matches(Regex("[0-9.]+"))) { "Expected IPv4 DNS" }
        val script = installed(ROUTE_ASSET, context)
        root("sh '" + script + "' start " + uid(context) + " '" + clientIpv4 + "' '" + dnsIpv4 + "'")
    }

    fun open(context: Context): Int {
        ensureInstalled(context)
        val socketName = "amnezia-root-" + Process.myPid() + "-" + System.nanoTime()
        val server = LocalServerSocket(socketName)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit(Callable<LocalSocket> { server.accept() })
        var process: java.lang.Process? = null
        try {
            val helper = installed(TUN_ASSET, context)
            process = ProcessBuilder("su", "-c", "'" + helper + "' awg0 " + socketName)
                .redirectErrorStream(true).start()
            val client = future.get(15, TimeUnit.SECONDS)
            client.use { connection ->
                connection.soTimeout = 5000
                check(connection.inputStream.read() == 1) { "Root TUN handshake failed" }
                val fd = connection.ancillaryFileDescriptors?.firstOrNull()
                    ?: error("Root helper did not send a TUN file descriptor")
                val ownedFd = ParcelFileDescriptor.dup(fd).detachFd()
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    ParcelFileDescriptor.adoptFd(ownedFd).close()
                    error("Root TUN helper exited with an error")
                }
                return ownedFd
            }
        } finally {
            try { server.close() } catch (_: Exception) {}
            future.cancel(true)
            pool.shutdownNow()
            process?.destroy()
        }
    }
}
