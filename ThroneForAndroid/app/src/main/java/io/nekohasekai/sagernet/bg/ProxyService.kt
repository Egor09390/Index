package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.tproxy.TProxySession
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

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
    private var vpnCallbacks = emptyList<ConnectivityManager.NetworkCallback>()
    private val vpnUpdateLock = Mutex()

    override suspend fun preInit() {
        super.preInit()
        if (DataStore.serviceMode == Key.MODE_TPROXY) upstreamVpn = defaultVpn()
    }

    override suspend fun lateInit() {
        super.lateInit()
        val session = tproxy ?: return
        val connectivity = SagerNet.connectivity
        fun observer() = object : ConnectivityManager.NetworkCallback() {
            private fun update() = runOnMainDispatcher { updateUpstreamVpn(session) }

            override fun onAvailable(network: Network) {
                update()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                update()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                update()
            }

            override fun onLost(network: Network) {
                update()
            }
        }
        val defaultObserver = observer()
        connectivity.registerDefaultNetworkCallback(defaultObserver)
        vpnCallbacks = listOf(defaultObserver)
        val vpn = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val vpnObserver = observer()
        connectivity.registerNetworkCallback(vpn, vpnObserver)
        vpnCallbacks = vpnCallbacks + vpnObserver
        updateUpstreamVpn(session)
    }

    private fun defaultVpn(): Network? {
        val connectivity = SagerNet.connectivity
        val network = connectivity.activeNetwork ?: return null
        return network.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    @Suppress("DEPRECATION")
    private fun androidVpnInterfaces(): Set<String> {
        val connectivity = SagerNet.connectivity
        // The VPN may apply to the browser without applying to Throne itself.
        return connectivity.allNetworks.mapNotNullTo(mutableSetOf()) { network ->
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                return@mapNotNullTo null
            }
            connectivity.getLinkProperties(network)?.interfaceName
        }
    }

    private suspend fun updateUpstreamVpn(session: TProxySession) = vpnUpdateLock.withLock {
        if (tproxy !== session || data.state != BaseService.State.Connected) return
        val currentVpn = defaultVpn()
        val routesChanged = try {
            withContext(Dispatchers.IO) { session.setAndroidVpnInterfaces(androidVpnInterfaces()) }
        } catch (error: IOException) {
            Logs.w(error)
            if (tproxy === session && data.state == BaseService.State.Connected) failRunner(getString(R.string.tproxy_stopped))
            return
        }
        if (tproxy !== session || data.state != BaseService.State.Connected) return
        if (!routesChanged && currentVpn == upstreamVpn) return
        upstreamVpn = currentVpn
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
        runInterruptible { session.start(androidVpnInterfaces()) }
    }

    override suspend fun killProcesses(): Throwable? {
        val session = tproxy
        tproxy = null
        for (callback in vpnCallbacks) {
            runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }.onFailure { Logs.w(it) }
        }
        vpnCallbacks = emptyList()
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
