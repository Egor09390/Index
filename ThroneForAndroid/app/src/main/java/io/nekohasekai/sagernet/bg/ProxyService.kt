package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.tproxy.TProxySession
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override var upstreamInterfaceName: String? = null

    @Volatile
    private var tproxy: TProxySession? = null

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
        val error = withContext(Dispatchers.IO) { runCatching { session?.close() }.exceptionOrNull() }
        val coreError = super.killProcesses()
        if (error != null && coreError != null) error.addSuppressed(coreError)
        return error ?: coreError
    }

    override fun onDestroy() {
        tproxy?.requestStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
