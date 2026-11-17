package org.amnezia.vpn

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.CONNECTING
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTING
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.putStatus
import org.amnezia.vpn.protocol.wireguard.Wireguard
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.Prefs
import org.json.JSONObject

/**
 * A normal foreground Service, not a VpnService. Supports AWG and WireGuard only.
 * Never calls VpnService.prepare(), Builder.establish() or VpnService.protect().
 */
class RootAwgService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(UNKNOWN)
    private val clients = mutableMapOf<Messenger, IpcMessenger>()
    private var connection: Job? = null
    private var tunnel: Wireguard? = null
    private var currentProto: VpnProto? = null
    private val notification by lazy { ServiceNotification(this) }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.extractIpcMessage<Action>()) {
                Action.REGISTER_CLIENT -> {
                    msg.replyTo?.let { clients[it] = IpcMessenger(it, msg.data.getString(MSG_CLIENT_NAME)) }
                }
                Action.UNREGISTER_CLIENT -> msg.replyTo?.let { clients.remove(it) }
                Action.CONNECT -> connect(msg.data.getString(MSG_VPN_CONFIG))
                Action.DISCONNECT -> disconnect()
                Action.REQUEST_STATUS -> msg.replyTo?.let {
                    IpcMessenger(it).send {
                        ServiceEvent.STATUS.packToMessage { putStatus(state.value) }
                    }
                }
                else -> {}
            }
        }
    }
    private val messenger = Messenger(handler)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DISCONNECT -> disconnect()
                ACTION_CONNECT -> connect(null)
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (state.value == CONNECTED) scope.launch(Dispatchers.IO) {
                try { tunnel?.refreshRootRouting() } catch (e: Exception) {
                    Log.e("RootAwgService", "Route refresh failed: " + e.message)
                }
            }
        }
    }
    private var networkBound = false

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, receiver,
            IntentFilter().apply { addAction(ACTION_CONNECT); addAction(ACTION_DISCONNECT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            state.collect { newState ->
                notification.updateNotification(null, currentProto?.label, newState)
                clients.send {
                    ServiceEvent.STATUS_CHANGED.packToMessage { putStatus(newState) }
                }
                VpnStateStore.store { VpnState(newState, vpnProto = currentProto) }
                if (newState == CONNECTED && !networkBound) {
                    try {
                        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                            .registerDefaultNetworkCallback(networkCallback)
                        networkBound = true
                    } catch (e: Exception) { Log.e("RootAwgService", "Network callback failed: " + e.message) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID,
            notification.buildNotification(null, "Root AWG", state.value), type)
        connect(intent?.getStringExtra(MSG_VPN_CONFIG))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun connect(config: String?) {
        if (connection?.isActive == true || state.value == CONNECTED) return
        if (!RootMode.isEnabled()) {
            onError("Root Mode is disabled")
            return
        }
        val data = config ?: Prefs.load<String>("AMNEZIA_ROOT_LAST_CONFIG")
        if (data.isBlank()) return
        connection = scope.launch(Dispatchers.IO) {
            try {
                val obj = JSONObject(data)
                val proto = VpnProto.get(obj.getString("protocol"))
                require(proto == VpnProto.AWG || proto == VpnProto.WIREGUARD) {
                    "Root mode supports AWG and WireGuard only"
                }
                currentProto = proto
                Prefs.save("AMNEZIA_ROOT_LAST_CONFIG", data)
                state.value = CONNECTING
                val wg = proto.protocol as Wireguard
                wg.initialize(applicationContext, state, ::onError)
                tunnel = wg
                wg.startRootVpn(obj)
            } catch (_: CancellationException) {
                // Disconnect handles cleanup and state transition.
            } catch (e: Exception) {
                onError(e.message ?: "Root tunnel failed")
                try { tunnel?.stopVpn() } catch (_: Exception) {}
                state.value = DISCONNECTED
            }
        }
    }

    private fun disconnect() {
        if (state.value == DISCONNECTED || state.value == DISCONNECTING) return
        state.value = DISCONNECTING
        scope.launch(Dispatchers.IO) {
            try {
                connection?.cancelAndJoin()
                tunnel?.stopVpn()
            } catch (e: Exception) {
                onError("Root shutdown: " + e.message)
            } finally {
                tunnel = null
                state.value = DISCONNECTED
            }
        }
    }

    private fun onError(detail: String) {
        Log.e("RootAwgService", detail)
        scope.launch {
            clients.send {
                ServiceEvent.ERROR.packToMessage { putString(MSG_ERROR, detail) }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (networkBound) {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {}
        }
        runBlocking(Dispatchers.IO) {
            connection?.cancelAndJoin()
            try { tunnel?.stopVpn() } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }
}
