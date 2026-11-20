package io.nekohasekai.sagernet.ui.settings

import android.os.Build
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.remove
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.update.UpdateScheduler
import io.nekohasekai.sagernet.utils.AlwaysOnVpn
import io.nekohasekai.sagernet.utils.BatteryOptimizations
import io.nekohasekai.sagernet.utils.OemBackground

/** Service mode, auto connect (remember_enable) and the connection behaviour switches. */
class GeneralSettingsFragment : SettingsScreenFragment(R.xml.settings_general) {

    private lateinit var autoConnect: SwitchPreference
    private lateinit var alwaysOn: Preference
    private lateinit var battery: Preference

    override fun bind() {
        pref<Preference>(Key.SERVICE_MODE).setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        // The boot receiver follows the switch at once, not only at the next service start.
        autoConnect = pref(SettingsRegistry.REMEMBER_ENABLE.key)
        autoConnect.setOnPreferenceChangeListener { _, newValue ->
            BootReceiver.enabled = newValue as Boolean
            true
        }
        if (Build.VERSION.SDK_INT < 28) {
            pref<Preference>(Key.METERED_NETWORK).remove()
        }
        pref<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS).setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }
        reloadOn(Key.METERED_NETWORK, Key.ALLOW_BYPASS)

        alwaysOn = pref(KEY_ALWAYS_ON_VPN)
        alwaysOn.setOnPreferenceClickListener {
            if (!AlwaysOnVpn.openSettings(requireContext())) toast(R.string.vpn_settings_missing)
            true
        }
        battery = pref(KEY_BATTERY_OPTIMIZATION)
        battery.setOnPreferenceClickListener {
            val context = requireContext()
            if (!BatteryOptimizations.launch(context, BatteryOptimizations.actionIntent(context))) {
                toast(R.string.battery_settings_missing)
            }
            true
        }
        pref<Preference>(KEY_BACKGROUND_RESTRICTIONS).setOnPreferenceClickListener {
            OemBackground.open(requireContext())
            true
        }

        pref<Preference>(KEY_UPDATES).isVisible = BuildConfig.IN_APP_UPDATER
        pref<Preference>(Key.UPDATE_CHECK_AUTO).setOnPreferenceChangeListener { _, newValue ->
            UpdateScheduler.schedule(enabled = newValue as Boolean)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        if (!::battery.isInitialized) return
        val context = requireContext()
        battery.setSummary(
            when (BatteryOptimizations.state(context)) {
                BatteryOptimizations.State.UNRESTRICTED -> R.string.battery_state_unrestricted
                BatteryOptimizations.State.OPTIMIZED -> R.string.battery_state_optimized
                BatteryOptimizations.State.RESTRICTED -> R.string.battery_state_restricted
            }
        )
        val alwaysOnState = AlwaysOnVpn.state()
        alwaysOn.setSummary(
            when (alwaysOnState) {
                AlwaysOnVpn.State.OFF -> R.string.always_on_vpn_off
                AlwaysOnVpn.State.ON -> R.string.always_on_vpn_on
                AlwaysOnVpn.State.LOCKDOWN -> R.string.always_on_vpn_lockdown
                AlwaysOnVpn.State.UNKNOWN -> R.string.always_on_vpn_unknown
            }
        )
        val superseded = alwaysOnState == AlwaysOnVpn.State.ON || alwaysOnState == AlwaysOnVpn.State.LOCKDOWN
        autoConnect.setSummary(if (superseded) R.string.auto_connect_superseded else R.string.auto_connect_summary)
    }

    private companion object {
        const val KEY_ALWAYS_ON_VPN = "alwaysOnVpn"
        const val KEY_BATTERY_OPTIMIZATION = "batteryOptimization"
        const val KEY_BACKGROUND_RESTRICTIONS = "backgroundRestrictions"
        const val KEY_UPDATES = "cag_updates"
    }
}
