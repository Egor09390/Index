package org.amnezia.vpn

import android.os.Build
import org.amnezia.vpn.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Explicit opt-in; persisted in the same multi-process preference store as the client.
 * Always disabled by default. Root is never requested by the standard VPN path.
 */
object RootMode {
    private const val PREF_ROOT_MODE = "AMNEZIA_ROOT_MODE_ENABLED"

    @JvmStatic
    fun isEnabled(): Boolean = Prefs.load<Boolean>(PREF_ROOT_MODE)

    @JvmStatic
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && (!Build.SUPPORTED_ABIS.contains("arm64-v8a") || !hasRoot())) {
            return false
        }
        Prefs.save(PREF_ROOT_MODE, enabled)
        return true
    }

    private fun hasRoot(): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0 && process.inputStream.bufferedReader().readText().trim() == "0"
            }
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }
}
