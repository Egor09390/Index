package io.nekohasekai.sagernet.bg.tproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Process
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import moe.matsuri.nb4a.utils.CoreLog

internal class TProxySession(
    private val context: Context,
    private val onUnexpectedStop: () -> Unit,
) : Closeable {
    private val ready = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val ports = PortLease()
    @Volatile private var process: java.lang.Process? = null

    @Volatile private var connected = false
    @Volatile private var closing = false
    @Volatile private var errorMessage: String? = null

    private val commands = Any()
    private var vpnInterfaces = emptySet<String>()

    /** Start su and the JVM while the main core is being prepared. The child waits for its config. */
    fun prepareRootProcess() {
        check(process == null && !closing)
        val appInfo = context.applicationInfo
        val abiRuntime = if (Process.is64Bit()) "/system/bin/app_process64" else "/system/bin/app_process32"
        val runtime = if (File(abiRuntime).canExecute()) abiRuntime else "/system/bin/app_process"
        val command = "export CLASSPATH=${quote(appInfo.sourceDir)} LD_LIBRARY_PATH=${quote(appInfo.nativeLibraryDir)}; " +
            "exec $runtime /system/bin ${TProxyRootMain::class.java.name}"
        val child = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            error(context.getString(R.string.tproxy_root_denied))
        }
        process = child
        thread(name = "TPROXY root process", isDaemon = true) {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        when {
                            line == TProxyRootMain.READY -> {
                                connected = true
                                ready.countDown()
                            }
                            line.startsWith("${TProxyRootMain.ERROR} ") -> {
                                errorMessage = line.substringAfter(' ').take(400)
                            }
                            line.startsWith("${TProxyRootMain.LOG} ") -> {
                                CoreLog.write("[TPROXY] ${line.substringAfter(' ')}")
                            }
                        }
                    }
                }
            } finally {
                stopped.countDown()
                ready.countDown()
                if (connected && !closing) onUnexpectedStop()
            }
        }
    }

    fun start(coreConfig: String, initialVpnInterfaces: Set<String>) {
        val config = JsonInput.parseObject(coreConfig)
        val mixed = config.array("inbounds")
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("type") == "mixed" && it.string("tag") == "mixed-in" }
        if (mixed == null || mixed.string("listen") !in listOf("127.0.0.1", "::", "0.0.0.0") ||
            mixed.int("listen_port") !in 1..65535) {
            error(context.getString(R.string.tproxy_no_ingress))
        }

        val target = jsonObjectOf("server" to "127.0.0.1", "server_port" to mixed.int("listen_port"))
        (mixed.array("users").firstOrNull() as? JsonObject)?.let {
            target["username"] = it.string("username")
            target["password"] = it.string("password")
        }
        val port = ports.reserve(1).single()
        check(port >= 1024)
        val bypass = JsonArray()
        if (!DataStore.disablePrivateRangeBypass) DataStore.vpnPrivateRanges.forEach { bypass.add(it) }
        // These are interface-local even when the user includes private unicast networks in the proxy.
        bypass.add("169.254.0.0/16")
        bypass.add("fe80::/10")
        val workDir = File(context.cacheDir, "tproxy")
        check(workDir.isDirectory || workDir.mkdirs()) { "Cannot create the TPROXY cache directory" }
        val vpnUids = vpnClientUids()
        val options = jsonObjectOf(
            "uid" to Process.myUid(), "port" to port, "target" to target, "bypass_ranges" to bypass,
            "vpn_uids" to JsonArray().apply { vpnUids.forEach { add(it) } },
            "reject_quic" to requiresQuicFallback(config),
            TProxyRootMain.VPN_INTERFACES to JsonValues.stringArray(initialVpnInterfaces.sorted()),
            "work_dir" to workDir.absolutePath,
        )

        val child = checkNotNull(process)
        try {
            synchronized(commands) {
                check(!closing)
                send(child, options)
                vpnInterfaces = initialVpnInterfaces
            }
        } catch (_: IOException) {
            error(context.getString(R.string.tproxy_root_denied))
        }
        if (!ready.await(30, TimeUnit.SECONDS)) error(context.getString(R.string.tproxy_root_timeout))
        if (!connected || stopped.count == 0L) {
            val message = errorMessage?.takeUnless { it == TProxyRootMain.ROOT_DENIED }
                ?: context.getString(R.string.tproxy_root_denied)
            error(context.getString(R.string.tproxy_start_failed, message))
        }
    }

    // Query before installing OUTPUT hooks so a VPN can start even when there is no VPN network yet.
    @Suppress("DEPRECATION")
    private fun vpnClientUids(): Set<Int> = context.packageManager.queryIntentServices(
        Intent(VpnService.SERVICE_INTERFACE),
        PackageManager.MATCH_DISABLED_COMPONENTS,
    ).mapNotNull { result ->
        val service = result.serviceInfo ?: return@mapNotNull null
        val uid = service.applicationInfo?.uid ?: return@mapNotNull null
        uid.takeIf { service.permission == Manifest.permission.BIND_VPN_SERVICE && it > 0 && it != Process.myUid() }
    }.toSortedSet()

    fun setAndroidVpnInterfaces(names: Set<String>): Boolean = synchronized(commands) {
        val child = process ?: return false
        if (closing || !connected || names == vpnInterfaces) return false
        send(child, jsonObjectOf(TProxyRootMain.VPN_INTERFACES to JsonValues.stringArray(names.sorted())))
        vpnInterfaces = names
        true
    }

    private fun send(child: java.lang.Process, command: JsonObject) {
        child.outputStream.write((command.toCompact() + "\n").toByteArray(Charsets.UTF_8))
        child.outputStream.flush()
    }

    fun requestStop() {
        closing = true
        synchronized(commands) {
            runCatching { process?.outputStream?.close() }
        }
    }

    override fun close() {
        requestStop()
        try {
            if (process != null && !stopped.await(15, TimeUnit.SECONDS)) {
                error(context.getString(R.string.tproxy_stop_timeout))
            }
            if (connected) errorMessage?.let { error(context.getString(R.string.tproxy_start_failed, it)) }
        } finally {
            ports.close()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
