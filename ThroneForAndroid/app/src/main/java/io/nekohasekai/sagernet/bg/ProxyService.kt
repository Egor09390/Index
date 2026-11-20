package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.tproxy.TProxySession
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"
    override fun createNotification(profileName: String): ServiceNotification =
        if (DataStore.serviceMode == Key.MODE_TPROXY) {
            ServiceNotification(this, profileName, "service-proxy", true, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ServiceNotification(this, profileName, "service-proxy", true)
        }

    override var upstreamInterfaceName: String? = null

    @Volatile
    private var tproxy: TProxySession? = null

    // Confined to the main thread, including callbacks and service cleanup.
    private var upstreamVpn: Network? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    override suspend fun preInit() {
        super.preInit()
        if (DataStore.serviceMode == Key.MODE_TPROXY) upstreamVpn = defaultVpn()
    }

    override suspend fun lateInit() {
        super.lateInit()
        val session = tproxy ?: return
        val connectivity = SagerNet.connectivity
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun update() = runOnMainDispatcher {
                if (tproxy === session && data.state == BaseService.State.Connected) {
                    updateUpstreamVpn()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                update()
            }

            override fun onLost(network: Network) {
                update()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            // On older Android the default-network callback follows only the physical network.
            val vpn = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerNetworkCallback(vpn, callback)
        }
        vpnCallback = callback
        Logs.i("[TPROXY] Android VPN available for Throne at start: ${defaultVpn() != null}")
        updateUpstreamVpn()
    }

    private fun defaultVpn(): Network? {
        val connectivity = SagerNet.connectivity
        val network = connectivity.activeNetwork ?: return null
        return network.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private suspend fun updateUpstreamVpn() {
        val currentVpn = defaultVpn()
        if (currentVpn == upstreamVpn) return
        upstreamVpn = currentVpn
        Logs.i(
            if (currentVpn != null) "[TPROXY] Android VPN is now the default network"
            else "[TPROXY] Android VPN is no longer the default network",
        )
        resetUpstreamConnections()
    }

    override suspend fun startProcesses() {
        val rootMode = DataStore.serviceMode == Key.MODE_TPROXY
        super.startProcesses()
        if (!rootMode) return
        val proxy = checkNotNull(data.proxy)
        val session = TProxySession(this, proxy.config.coreConfig) {
            runOnMainDispatcher {
                if (data.proxy === proxy && data.state.canStop) failRunner(getString(R.string.tproxy_stopped))
            }
        }
        tproxy = session
        runInterruptible { session.start() }
    }

    override suspend fun killProcesses(): Throwable? {
        val session = tproxy
        tproxy = null
        vpnCallback?.let { callback ->
            runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }.onFailure { Logs.w(it) }
        }
        vpnCallback = null
        upstreamVpn = null
        val error = withContext(Dispatchers.IO) { runCatching { session?.close() }.exceptionOrNull() }
        val coreError = super.killProcesses()
        if (error != null && coreError != null) error.addSuppressed(coreError)
        return error ?: coreError
    }

    override fun onDestroy() {
        tproxy?.requestStop()
        if (data.state.canStop) stopRunner()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
