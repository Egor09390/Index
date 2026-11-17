package org.amnezia.vpn

import android.os.Build
import java.util.concurrent.TimeUnit

/** This build is root-only. Never fall back to Android VpnService. */
object RootMode {
    @JvmStatic
    fun isEnabled(): Boolean = true

    /** Fail visibly if root is unavailable; never silently start a platform VPN. */
    fun requireRoot() {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "Root AWG requires an ARM64 Android device"
        }
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true).start()
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Root authorization timed out; grant su access to Amnezia")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.exitValue() == 0 && output == "0") {
                "Root authorization denied: grant su access to Amnezia"
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Root executable 'su' unavailable", e)
        } finally {
            process?.destroy()
        }
    }
}
