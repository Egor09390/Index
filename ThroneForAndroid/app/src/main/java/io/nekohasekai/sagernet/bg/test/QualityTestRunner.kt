package io.nekohasekai.sagernet.bg.test

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.bg.proto.toStartOptions
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Measures only one saved profile at a time. The probe box's authenticated loopback inlet has an explicit exit. */
internal class QualityTestRunner(private val session: TestSession) {
    @Volatile private var sampler: QualitySampler? = null

    fun cancel() { sampler?.cancel() }

    suspend fun run(profiles: List<ProxyEntity>) {
        for (profile in profiles) {
            if (session.cancelled) break
            val environment = QualityEnvironment.describe(app)
            val report = queue.withPermit {
                delay((nextProfileAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (session.cancelled) return
                val network = SagerNet.underlyingNetwork
                try {
                    val measured = measure(profile)
                    if (network != SagerNet.underlyingNetwork) QualityReport(null, "Во время замера сменилась сеть")
                    else measured
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    QualityReport(null, "Не удалось запустить проверку профиля")
                } finally {
                    nextProfileAt = SystemClock.elapsedRealtime() + 350
                }
            }
            if (!session.cancelled) session.reportQuality(profile, report.copy(details = report.details + "\n" + environment))
        }
    }

    private suspend fun measure(profile: ProxyEntity): QualityReport {
        val ports = PortLease()
        try {
            val generated = CoreConfigs.buildTest(listOf(profile.id), ports)
            if (!generated.ok || generated.skipped.containsKey(profile.id)) return QualityReport(null, "Профиль не подготовлен к замеру")
            session.ensureSharedXrayAssets(generated.xrayConfig)
            for (config in generated.xrayFullConfigs) session.ensureSharedXrayAssets(config)
            if (session.cancelled) return QualityReport(null, "Профиль не подготовлен к замеру")

            val fullConfig = generated.fullConfigs[profile.id]
            val core = if (fullConfig == null) CoreConfig.from(generated) else CoreConfig(fullConfig)
            val config = JsonInput.parseObject(core.coreConfig)
            val route = config["route"] as? JsonObject ?: JsonObject().also { config["route"] = it }
            route["auto_detect_interface"] = true
            val exit = generated.outboundTags.singleOrNull() ?: defaultExit(config, route) ?: return QualityReport(null, "Профиль не подготовлен к замеру")

            val port = ports.reserve(1).single()
            if (port == 0) return QualityReport(null, "Профиль не подготовлен к замеру")
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

            val unresolved = prepareQualityDns(config, SagerNet.underlyingNetwork)
            if (session.cancelled) throw CancellationException()
            val options = core.copy(coreConfig = config.toCompact()).toStartOptions()
            val test = QualitySampler(port, PROBE_USER, password, DataStore.qualityGatewayUrl)
            val instance = Mobile.newInstance(CoreRuntime.platform, options)
            try {
                sampler = test
                if (session.cancelled) throw CancellationException()
                instance.start()
                val unresolvedXray = warmQualityXrayDns(core, instance)
                if (session.cancelled) throw CancellationException()
                val report = test.measure()
                val failures = unresolved + unresolvedXray
                if (failures == 0) return report
                return report.copy(details = report.details + "\nDNS до старта: адреса получены не для всех узлов ($failures)")
            } finally {
                sampler = null
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

    private companion object {
        val queue = Semaphore(1)
        var nextProfileAt = 0L
        const val LOOPBACK = "127.0.0.1"
        const val PROBE_USER = "quality"
        const val PROBE_INBOUND = "throne-quality-probe"
    }
}
