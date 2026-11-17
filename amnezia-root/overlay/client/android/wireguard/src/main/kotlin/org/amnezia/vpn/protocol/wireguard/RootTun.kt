package org.amnezia.vpn.protocol.wireguard

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root process creates an actual kernel TUN device, passing only its FD to wg-go.
 * The app process and its WireGuard Go backend never run as root.
 *
 * The two assets are installed by the fork's Android build job for arm64-v8a.
 */
object RootTun {
    private const val TUN_ASSET = "amnezia-root-tun-arm64"
    private const val ROUTE_ASSET = "amnezia-root-routing.sh"
    private val assets = listOf(TUN_ASSET, ROUTE_ASSET)

    private fun uid(context: Context): Int = context.applicationInfo.uid
    private fun installed(name: String, context: Context): String =
        "/data/local/tmp/amnezia-" + uid(context) + "-" + name

    // Some root managers expose su through an absolute location and do not
    // include it in the isolated app process's PATH. Do not use a system
    // shell fallback: this APK must either get uid 0 or fail visibly.
    private val suCandidates = listOf(
        "su", "/system/bin/su", "/system/xbin/su",
        "/sbin/su", "/su/bin/su", "/debug_ramdisk/su"
    )

    private fun startSu(command: String): java.lang.Process {
        val problems = mutableListOf<String>()
        for (binary in suCandidates) {
            try {
                val builder = ProcessBuilder(binary, "-c", command)
                    .redirectErrorStream(true)
                val oldPath = builder.environment()["PATH"] ?: ""
                builder.environment()["PATH"] = listOf(
                    oldPath, "/system/bin", "/system/xbin", "/sbin", "/su/bin",
                    "/debug_ramdisk", "/vendor/bin"
                ).filter { it.isNotBlank() }.joinToString(":")
                return builder.start()
            } catch (e: IOException) {
                problems += binary + " (" + (e.message ?: "not executable") + ")"
            }
        }
        throw IllegalStateException(
            "No su executable accessible to Amnezia. Grant org.amnezia.vpn " +
            "root access in Magisk/KernelSU/APatch; check root-manager hiding. " +
            "Checked: " + problems.joinToString("; ").take(380)
        )
    }

    /** Check root inside the actual service process before changing any routes. */
    fun requireRoot() {
        val uid = root("id -u", timeout = 45)
            .lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        check(uid == "0") {
            "su did not grant uid 0 to Amnezia. Allow root for org.amnezia.vpn " +
            "in your root manager (received: " + (uid ?: "empty").take(60) + ")"
        }
    }

    private fun root(command: String, timeout: Long = 45): String {
        val p = startSu(command)
        try {
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                error("Root authorization timed out. Allow org.amnezia.vpn in the root manager.")
            }
            val output = p.inputStream.bufferedReader().readText()
            if (p.exitValue() != 0) {
                error("su denied root command (exit " + p.exitValue() +
                    "). Grant Amnezia root access. Details: " + output.take(380))
            }
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

    fun cleanup(context: Context, strict: Boolean = false) {
        val script = installed(ROUTE_ASSET, context)
        try {
            root("test ! -f '" + script + "' || sh '" + script + "' stop " + uid(context))
        } catch (e: Exception) {
            // Do not start a new tunnel with stale global IP rules.
            if (strict) throw IllegalStateException("Cannot remove stale Root TUN routing: " + e.message, e)
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
            process = startSu("'" + helper + "' awg0 " + socketName)
            val client = try {
                future.get(45, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                val failure = if (!process.isAlive) {
                    process.inputStream.bufferedReader().readText().take(380)
                } else {
                    "root prompt unanswered or TUN helper blocked by SELinux"
                }
                throw IllegalStateException("Root TUN helper did not connect: " + failure, e)
            }
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
