package org.amnezia.vpn

import android.os.Build
import android.content.Context
import org.amnezia.vpn.protocol.wireguard.RootTun

/** Root-only APK: never falls back to Android's single VpnService slot. */
object RootMode {
    @JvmStatic
    fun isEnabled(): Boolean = true

    fun requireRoot(context: Context) {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "Root AWG requires an ARM64 Android device"
        }
        // Try the regular su and the allowlisted KernelSU native root launcher.
        // DFRoot late-load can hide su even after the app has already been
        // authorized in KernelSU. Never treat a failed root probe as success.
        RootTun.requireRoot(context)
    }
}
