package io.nekohasekai.sagernet.ui.settings

import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.route.RouteQuickSwitch
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.MTUPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import kotlin.math.abs

/** Tun Settings: stack, MTU, IPv6, tun routing, private range bypass, per-app proxy and the tun addresses. */
class TunSettingsFragment : SettingsScreenFragment(R.xml.settings_tun) {

    private lateinit var proxyApps: SwitchPreference

    override fun bind() {
        reloadOn(
            SettingsRegistry.VPN_MTU.key, SettingsRegistry.VPN_IPV6.key,
            SettingsRegistry.ENABLE_TUN_ROUTING.key,
        )
        val mtu = pref<MTUPreference>(SettingsRegistry.VPN_MTU.key)
        val vpnIpv6 = pref<SwitchPreference>(SettingsRegistry.VPN_IPV6.key)
        validation.bind(mtu.key, Validation.Rules.int(MIN_MTU.toLong(), MAX_MTU.toLong(), Empty.Required)).gate()
        validation.addRule(listOf(mtu.key, vpnIpv6.key), Severity.Warning, mtu.title?.toString()) {
            val value = Validation.toInt(mtu.value.orEmpty())
            if (!vpnIpv6.isChecked || value == null || value >= MIN_IPV6_MTU || value < MIN_MTU) ""
            else vtr(R.string.validation_mtu_ipv6, MIN_IPV6_MTU.toString())
        }

        val ranges = pref<StringListPreference>(SettingsRegistry.VPN_PRIVATE_RANGES.key)
        val bypass = pref<SwitchPreference>(KEY_BYPASS_PRIVATE_RANGES)
        bypass.isChecked = !DataStore.disablePrivateRangeBypass
        ranges.isEnabled = bypass.isChecked
        bypass.setOnPreferenceChangeListener { _, newValue ->
            DataStore.disablePrivateRangeBypass = !(newValue as Boolean)
            ranges.isEnabled = newValue
            needReload()
            true
        }
        val saved = DataStore.vpnPrivateRanges
        val widened = saved.map(SettingChecks::withPrefixLength)
        if (widened != saved) ranges.showUnsaved(widened.joinToString("\n"))
        reloadOn(ranges.key)
        // Checked only while the bypass is on: the list is disabled, neither checked nor used, while it is off.
        validation.bindLines(ranges.key, SettingChecks::privateRange).gate()
        pref<Preference>(KEY_RESTORE_RANGES).setOnPreferenceClickListener {
            ranges.text = SettingsRegistry.DEFAULT_PRIVATE_RANGES.joinToString("\n")
            needReload()
            true
        }

        val ipv4 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV4_CIDR.key)
        val ipv6 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV6_CIDR.key)
        gateText(ipv4.key, Validation.Rules.cidr(Family.V4), invalid = R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = false) }
        gateText(ipv6.key, Validation.Rules.cidr(Family.V6), invalid = R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = true) }
        pref<Preference>(KEY_RESTORE_ADDRESSES).setOnPreferenceClickListener {
            ipv4.text = SettingsRegistry.DEFAULT_TUN_IPV4_CIDR
            ipv6.text = SettingsRegistry.DEFAULT_TUN_IPV6_CIDR
            needReload()
            true
        }

        proxyApps = pref(Key.PROXY_APPS)
        // Only switching it on opens the app list: that screen turns per-app proxy on, so it could never go off here.
        proxyApps.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                DataStore.dirty = true
                startActivity(Intent(activity, AppManagerActivity::class.java))
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::proxyApps.isInitialized) proxyApps.isChecked = DataStore.proxyApps
    }

    private companion object {
        const val MIN_MTU = 1000
        const val MAX_MTU = 10000
        const val MIN_IPV6_MTU = 1280
        const val KEY_BYPASS_PRIVATE_RANGES = "tunBypassPrivateRanges"
        const val KEY_RESTORE_RANGES = "tunRestoreRanges"
        const val KEY_RESTORE_ADDRESSES = "tunRestoreAddresses"
    }
}

/**
 * Routing Settings › Common (domain strategies, rule-set mirror), AdBlock and the remote route profile auto update;
 * the route profiles themselves live on the Route screen.
 */
class RoutingSettingsFragment : SettingsScreenFragment(R.xml.settings_routing) {

    override fun bind() {
        pref<Preference>(KEY_ROUTE_PROFILES).setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
            true
        }
        pref<Preference>(KEY_CURRENT_ROUTE).setOnPreferenceClickListener {
            RouteQuickSwitch.show(this) { showCurrentRoute() }
            true
        }

        reloadOn(
            SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY.key, SettingsRegistry.DOMAIN_STRATEGY.key,
            SettingsRegistry.RULESET_MIRROR.key, SettingsRegistry.ADBLOCK_ENABLE.key,
        )
        for ((key, note) in listOf(
            SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY.key to R.string.outbound_domain_strategy_sum,
            SettingsRegistry.DOMAIN_STRATEGY.key to R.string.resolve_domain_strategy_sum,
        )) {
            pref<SimpleMenuPreference>(key).summaryProvider =
                Preference.SummaryProvider<SimpleMenuPreference> { "${it.entry}\n${getString(note)}" }
        }

        val enabled = pref<SwitchPreference>(KEY_AUTO_UPDATE)
        val interval = pref<EditTextPreference>(KEY_AUTO_UPDATE_INTERVAL)
        fun minutes() = abs(DataStore.routeAutoUpdate).takeIf { it > 0 } ?: -SettingsRegistry.ROUTE_AUTO_UPDATE.default

        enabled.isChecked = DataStore.routeAutoUpdate > 0
        interval.text = minutes().toString()
        interval.isEnabled = enabled.isChecked
        interval.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            getString(R.string.auto_update_interval_sum, minutes())
        }
        interval.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.routeAutoUpdate = if (on) minutes() else -minutes()
            interval.isEnabled = on
            RemoteRouteUpdater.schedule()
            true
        }
        interval.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
            DataStore.routeAutoUpdate = if (enabled.isChecked) value else -value
            RemoteRouteUpdater.schedule()
            true
        }
        validation.bind(interval.key, SettingChecks.autoUpdateMinutes).gate()
    }

    override fun onResume() {
        super.onResume()
        showCurrentRoute()
    }

    private fun showCurrentRoute() {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { RouteManager.current().name }
            findPreference<Preference>(KEY_CURRENT_ROUTE)?.summary = name
        }
    }

    private companion object {
        const val KEY_AUTO_UPDATE = "routeAutoUpdateEnabled"
        const val KEY_AUTO_UPDATE_INTERVAL = "routeAutoUpdateInterval"
        const val KEY_ROUTE_PROFILES = "routeProfiles"
        const val KEY_CURRENT_ROUTE = "currentRouteProfile"
    }
}
