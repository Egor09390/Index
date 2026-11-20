package io.nekohasekai.sagernet.ui.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.profile.keepUnlistedValue
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import io.nekohasekai.sagernet.widget.ExitIp
import moe.matsuri.nb4a.ui.LongClickListPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.UrlTestPreference
import kotlin.math.abs

/** Basic Settings › Common › Testing. */
class TestingSettingsFragment : SettingsScreenFragment(R.xml.settings_testing) {

    override fun bind() {
        bindUrlTest()
        gateText(
            SettingsRegistry.DIRECT_TEST_URL.key, Validation.Rules.url(SettingChecks.HTTP), reload = false,
            invalid = R.string.speed_test_url_invalid,
        ) { it.isEmpty() || SpeedTestSettings.isValidHttpUrl(it) }
        pref<EditTextPreference>(SettingsRegistry.DIRECT_TEST_URL.key).summaryProvider =
            DefaultSummaryProvider(getString(R.string.direct_test_url_empty))

        // Off drops the shown exit and a lookup in flight; on waits for the next connection or stats bar tap.
        pref<SwitchPreference>(SettingsRegistry.EXIT_COUNTRY_LOOKUP.key).setOnPreferenceChangeListener { _, newValue ->
            if (newValue == false) ExitIp.clear()
            true
        }
        // A newer desktop's service id stays stored (it runs as "auto" here) instead of being replaced by the menu.
        pref<SimpleMenuPreference>(SettingsRegistry.EXIT_LOOKUP_PROVIDER.key).keepUnlistedValue()

        // Only the URL test treats 0 as its default; the download/upload test would time out at once.
        gateInt(SettingsRegistry.SPEED_TEST_TIMEOUT_MS.key, { SettingChecks.millis(it, 5000, zeroIsDefault = false) }, reload = false)

        val simpleUrl = pref<EditTextPreference>(SettingsRegistry.SIMPLE_DL_URL.key)
        simpleUrl.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        gateText(
            simpleUrl.key, Validation.Rules.url(SettingChecks.HTTP, Empty.Required), reload = false,
            invalid = R.string.speed_test_url_invalid, valid = SpeedTestSettings::isValidHttpUrl,
        )
        simpleUrl.isEnabled = DataStore.speedTestMode == SpeedTestSettings.SIMPLE_DOWNLOAD
        pref<SimpleMenuPreference>(SettingsRegistry.SPEED_TEST_MODE.key).setOnPreferenceChangeListener { _, newValue ->
            simpleUrl.isEnabled = (newValue as String).toIntOrNull() == SpeedTestSettings.SIMPLE_DOWNLOAD
            true
        }
    }

    /**
     * The URL test's concurrency and timeout are edited in its URL dialog: their problems refuse that dialog's commit,
     * and the stored values mark the URL row.
     */
    private fun bindUrlTest() {
        val urlTest = pref<UrlTestPreference>(SettingsRegistry.TEST_URL.key)
        validation.bind(urlTest.key, Validation.Rules.url(SettingChecks.HTTP, Empty.Required)).gate()
        fun problems(concurrency: String, timeout: String): List<String> = listOf(
            // The desktop allows 0, but the stored setting must be positive: the dialog would save 0 as 5.
            R.string.test_concurrency to Validation.int(concurrency, 1, 499, Empty.Required),
            R.string.connection_test_timeout to SettingChecks.millis(timeout, 3000, zeroIsDefault = true),
        ).filter { it.second.isNotEmpty() }.map { vtr(R.string.validation_form_issue, getString(it.first), it.second) }

        val save = urlTest.onPreferenceChangeListener
        urlTest.setOnPreferenceChangeListener { preference, newValue ->
            val problem = problems(
                urlTest.concurrent?.text?.toString() ?: DataStore.testConcurrent.toString(),
                urlTest.timeout?.text?.toString() ?: DataStore.urlTestTimeoutMs.toString(),
            ).firstOrNull()
            if (problem != null) {
                refuse(preference, problem)
                false
            } else {
                save?.onPreferenceChange(preference, newValue) ?: true
            }
        }
        validation.bind(urlTest.key) {
            problems(DataStore.testConcurrent.toString(), DataStore.urlTestTimeoutMs.toString()).joinToString("\n")
        }
    }
}

/**
 * Logging, statistics, the Clash API, the sing-box API, Xray import preference,
 * certificate defaults and the NTP client.
 */
class CoreSettingsFragment : SettingsScreenFragment(R.xml.settings_core) {

    // After the write, so the sort reads the new value: Type/Security keys depend on skip_cert (desktop 6906912f).
    private val skipCertListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key != SettingsRegistry.SKIP_CERT.key) return
            runOnIoDispatcher {
                for (group in SagerDatabase.groupDao.allGroups()) {
                    val method = group.sort.method
                    if (method == GroupSortMethod.BY_TYPE || method == GroupSortMethod.BY_SECURITY) {
                        GroupSort.resortLater(group.id)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.configurationStore.unregisterChangeListener(skipCertListener)
        super.onDestroy()
    }

    override fun bind() {
        DataStore.configurationStore.registerChangeListener(skipCertListener)
        val logLevel = pref<LongClickListPreference>(SettingsRegistry.LOG_LEVEL.key)
        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true
            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = moe.matsuri.nb4a.utils.CoreLog.DEFAULT_SIZE_KB
                setText(size.toString())
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.log_buffer_size)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toIntOrNull()?.takeIf { it > 0 }
                        ?.coerceIn(moe.matsuri.nb4a.utils.CoreLog.MIN_SIZE_KB, moe.matsuri.nb4a.utils.CoreLog.MAX_SIZE_KB) ?: 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        reloadOn(
            SettingsRegistry.XRAY_LOG_LEVEL.key, SettingsRegistry.ENABLE_STATS.key,
            SettingsRegistry.DISABLE_TRAFFIC_STATS.key, SettingsRegistry.SKIP_CERT.key,
            SettingsRegistry.USE_MOZILLA_CERTS.key, SettingsRegistry.XRAY_USE_SYSTEM_CA.key,
            SettingsRegistry.NTP_OUTBOUND.key,
        )

        bindClashApi()
        bindBoxApi()
        bindNtp()
    }

    /** core_box_clash_api is one sign-encoded port: at most 0 is off, the magnitude is kept while off. */
    private fun bindClashApi() {
        val enabled = pref<SwitchPreference>(KEY_CLASH_ENABLED)
        val port = pref<EditTextPreference>(KEY_CLASH_PORT)
        val listen = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_CLASH_LISTEN_ADDR.key)
        val secret = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_CLASH_API_SECRET.key)
        fun portValue() = abs(DataStore.coreBoxClashApi).takeIf { it > 0 } ?: -SettingsRegistry.CORE_BOX_CLASH_API.default
        fun sync(on: Boolean) {
            port.isEnabled = on
            listen.isEnabled = on
            secret.isEnabled = on
        }

        enabled.isChecked = DataStore.clashApiEnabled
        port.text = portValue().toString()
        port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        secret.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        sync(enabled.isChecked)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.coreBoxClashApi = if (on) portValue() else -portValue()
            sync(on)
            needReload()
            true
        }
        port.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || !SettingValidators.isPort(value)) {
                toast(R.string.invalid_port, newValue?.toString().orEmpty())
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxClashApi = if (enabled.isChecked) value else -value
            needReload()
            true
        }
        validation.bind(port.key, SettingChecks.listenPort(SettingChecks.Listener.CLASH_API)).gate()
        // Empty listens on the loopback address, as the desktop's placeholder says.
        gateText(listen.key, Validation.Rules.host(Empty.Allowed))
        listen.summaryProvider = DefaultSummaryProvider(SettingsRegistry.LOOPBACK_ADDRESS)
        checkText(secret.key, R.string.invalid_value) { true }
    }

    /**
     * core_box_api_port, sign-encoded like core_box_clash_api. The secret is
     * never stored empty (sing-box reads that as no authentication): clearing it makes a new random one.
     */
    private fun bindBoxApi() {
        val enabled = pref<SwitchPreference>(KEY_API_ENABLED)
        val port = pref<EditTextPreference>(KEY_API_PORT)
        val secret = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_API_SECRET.key)
        fun portValue() = abs(DataStore.coreBoxApiPort).takeIf { it > 0 } ?: abs(SettingsRegistry.CORE_BOX_API_PORT.default)
        fun sync(on: Boolean) {
            port.isEnabled = on
            secret.isEnabled = on
        }

        enabled.isChecked = DataStore.boxApiEnabled
        port.text = portValue().toString()
        port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        secret.text = DataStore.coreBoxApiSecret
        secret.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        sync(enabled.isChecked)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.coreBoxApiPort = if (on) portValue() else -portValue()
            sync(on)
            needReload()
            true
        }
        port.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || !SettingValidators.isPort(value)) {
                toast(R.string.invalid_port, newValue?.toString().orEmpty())
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxApiPort = if (enabled.isChecked) value else -value
            needReload()
            true
        }
        validation.bind(port.key, SettingChecks.listenPort(SettingChecks.Listener.BOX_API)).gate()
        secret.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim().orEmpty()
            // Reading an empty secret back generates and saves a new one.
            if (value.isEmpty()) DataStore.coreBoxApiSecret = ""
            secret.text = value.ifEmpty { DataStore.coreBoxApiSecret }
            needReload()
            false
        }
    }

    private fun bindNtp() {
        val enable = pref<SwitchPreference>(SettingsRegistry.ENABLE_NTP.key)
        val fields = listOf(
            SettingsRegistry.NTP_SERVER_ADDRESS.key, SettingsRegistry.NTP_SERVER_PORT.key,
            SettingsRegistry.NTP_INTERVAL.key, SettingsRegistry.NTP_OUTBOUND.key,
        ).map { pref<Preference>(it) }
        fun sync(on: Boolean) = fields.forEach { it.isEnabled = on }
        sync(enable.isChecked)
        enable.setOnPreferenceChangeListener { _, newValue ->
            sync(newValue as Boolean)
            needReload()
            true
        }
        gateText(SettingsRegistry.NTP_SERVER_ADDRESS.key, Validation.Rules.host(Empty.Allowed))
        // The core turns port 0 into 123.
        gateInt(SettingsRegistry.NTP_SERVER_PORT.key, { if (Validation.toInt(it) == 0L) "" else Validation.port(it, Empty.Allowed) }, blankAs = 0)
        gateText(
            SettingsRegistry.NTP_INTERVAL.key, Validation.Rules.duration(),
            invalid = R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty,
        )
        pref<EditTextPreference>(SettingsRegistry.NTP_SERVER_PORT.key).summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { preference ->
                val text = preference.text.orEmpty()
                if (text.isEmpty() || text == "0") getString(R.string.setting_default) else text
            }
        pref<EditTextPreference>(SettingsRegistry.NTP_INTERVAL.key).summaryProvider =
            DefaultSummaryProvider("30m")
    }

    private companion object {
        const val KEY_CLASH_ENABLED = "coreClashApiEnabled"
        const val KEY_CLASH_PORT = "coreClashApiPort"
        const val KEY_API_ENABLED = "coreBoxApiEnabled"
        const val KEY_API_PORT = "coreBoxApiPort"
    }
}
