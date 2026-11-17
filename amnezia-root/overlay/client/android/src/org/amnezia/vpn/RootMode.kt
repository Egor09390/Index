package org.amnezia.vpn

import android.os.Build
import org.amnezia.vpn.protocol.wireguard.RootTun

/** Root-only APK: never falls back to Android's single VpnService slot. */
object RootMode {
    @JvmStatic
    fun isEnabled(): Boolean = true

    fun requireRoot() {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "Root AWG requires an ARM64 Android device"
        }
        // Use the exact same su discovery as every privileged TUN/route action.
        // A root manager can expose su in a nonstandard path, or hide it until
        // this app is explicitly authorized. Never treat a missing su as success.
        RootTun.requireRoot()
    }
}
