#!/usr/bin/env python3
"""Apply the root-AWG experimental overlay to a clean amnezia-vpn/amnezia-client dev checkout.

Fails closed if upstream changes an anchor; never silently patches the wrong release.
"""
import argparse
import re
import shutil
from pathlib import Path


def edit(root: Path, relative: str, old: str, new: str, expected: int = 1) -> None:
    file = root / relative
    content = file.read_text(encoding="utf-8")
    count = content.count(old)
    if count != expected:
        raise RuntimeError(f"{relative}: expected {expected} occurrences of {old[:60]!r}, found {count}")
    file.write_text(content.replace(old, new), encoding="utf-8")


def apply(root: Path, helper: Path) -> None:
    here = Path(__file__).resolve().parent
    overlay = here / "overlay"
    for original in overlay.rglob("*"):
        if original.is_file():
            target = root / original.relative_to(overlay)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, target)

    assets = root / "client/android/assets"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(here / "root-routing.sh", assets / "amnezia-root-routing.sh")
    shutil.copy2(helper, assets / "amnezia-root-tun-arm64")

    activity = "client/android/src/org/amnezia/vpn/AmneziaActivity.kt"
    edit(root, activity, 'Intent(this, proto.serviceClass)',
         'Intent(this, serviceClassFor(proto))', expected=2)
    # BIND_ABOVE_CLIENT and BIND_AUTO_CREATE are distinct bit flags. AND was
    # always zero; a root service that is starting could not be bound reliably.
    edit(root, activity,
         'bindService(it, serviceConnection, BIND_ABOVE_CLIENT and BIND_AUTO_CREATE)',
         'bindService(it, serviceConnection, BIND_ABOVE_CLIENT or BIND_AUTO_CREATE)')
    edit(root, activity,
         '''                if (AmneziaVpnService.isRunning(applicationContext, proto.processName)) {
                    doBindService()
                }''',
         '''                val runningProcess = if (RootMode.isEnabled()) {
                    "$packageName:amneziaRootAwgService"
                } else {
                    proto.processName
                }
                if (AmneziaVpnService.isRunning(applicationContext, runningProcess)) {
                    doBindService()
                }''')
    edit(root, activity,
         'if (proto.serviceClass == vpnProto?.serviceClass) {',
         'if (serviceClassFor(proto) == vpnProto?.let(::serviceClassFor)) {')
    edit(root, activity,
         '    private fun getVpnProto(vpnConfig: String): VpnProto? = try {',
         '''    private fun serviceClassFor(proto: VpnProto): Class<*> =
        RootAwgService::class.java

    private fun getVpnProto(vpnConfig: String): VpnProto? = try {''')
    edit(root, activity,
         '    private fun checkVpnPermission(onPermissionGranted: () -> Unit) {\n',
         '''    private fun checkVpnPermission(onPermissionGranted: () -> Unit) {
        if (RootMode.isEnabled()) {
            onPermissionGranted()
            return
        }
''')
    edit(root, activity,
         '''        getVpnProto(vpnConfig)?.let { proto ->
            Log.v(TAG, "Proto from config: $proto, current proto: $vpnProto")
''',
         '''        getVpnProto(vpnConfig)?.let { proto ->
            Log.v(TAG, "Proto from config: $proto, current proto: $vpnProto")
            if (RootMode.isEnabled() && proto != VpnProto.AWG && proto != VpnProto.WIREGUARD) {
                Toast.makeText(this, "Root mode supports AWG/WireGuard only", Toast.LENGTH_LONG).show()
                QtAndroidController.onServiceError()
                return@let
            }
            if (RootMode.isEnabled() && listOf(VpnProto.AWG, VpnProto.OPENVPN, VpnProto.XRAY).any {
                    AmneziaVpnService.isRunning(applicationContext, it.processName)
                }) {
                Toast.makeText(this,
                    "Stop the standard Amnezia VPN first, then connect with Root Mode",
                    Toast.LENGTH_LONG).show()
                QtAndroidController.onServiceError()
                return@let
            }
''')

    wg = "client/android/wireguard/src/main/kotlin/org/amnezia/vpn/protocol/wireguard/Wireguard.kt"
    edit(root, wg, '    private var tunnelHandle: Int = -1',
         '    private var tunnelHandle: Int = -1\n    private var rootActive = false')
    edit(root, wg, '    private fun start(\n', '''    /**
     * Root TUN path: retain the upstream AWG 3.1 UAPI serialization and Go backend.
     * The root helper handles ONLY TUN creation; encryption keys remain in-process.
     */
    fun startRootVpn(json: JSONObject) {
        check(tunnelHandle == -1) { "Tunnel already running" }
        val parsed = parseConfig(json)
        val data = json.getJSONObject(
            if (json.has("awg_config_data")) "awg_config_data" else "wireguard_config_data"
        )
        val cidr = data.getString("client_ip").split(",")
            .map { it.trim() }.firstOrNull { it.contains(".") && it.contains("/") }
            ?: throw VpnStartException("Root mode requires an IPv4 client address")
        val dns = json.optString("dns1", "").trim()
        RootTun.prepare(context)
        RootTun.cleanup(context, strict = true)
        try {
            val fd = RootTun.open(context)
            tunnelHandle = GoBackend.awgTurnOn("awg0", fd, parsed.toWgUserspaceString())
            if (tunnelHandle < 0) throw VpnStartException("AWG Go backend could not open the root TUN")
            RootTun.installRouting(context, cidr, dns)
            config = parsed
            rootActive = true
            launchStatusJob()
        } catch (e: Exception) {
            if (tunnelHandle >= 0) GoBackend.awgTurnOff(tunnelHandle)
            tunnelHandle = -1
            RootTun.cleanup(context)
            throw e
        }
    }

    fun refreshRootRouting() {
        if (rootActive) RootTun.refresh(context)
    }

    private fun start(
''')
    edit(root, wg,
         '''    override fun stopVpn() {
        if (tunnelHandle == -1) {''',
         '''    override fun stopVpn() {
        if (rootActive) {
            try {
                if (tunnelHandle != -1) turnOffVpn()
            } finally {
                RootTun.cleanup(context)
                rootActive = false
                state.value = DISCONNECTED
            }
            return
        }
        if (tunnelHandle == -1) {''')

    manifest = "client/android/AndroidManifest.xml"
    edit(root, manifest,
         '''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />''',
         '''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />''')
    edit(root, manifest,
         '''        <service
            android:name=".AwgService"''',
         '''        <!-- Ordinary service: does not reserve Android's VpnService slot. -->
        <service
            android:name=".RootAwgService"
            android:process=":amneziaRootAwgService"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User-initiated root TUN VPN underlay" />
        </service>

        <service
            android:name=".AwgService"''')

    # Remove the previous settings toggle and the unused C++ settings methods:
    # this build never enables Android's platform VPN, even when root is denied.
    manifest_file = root / manifest
    xml = manifest_file.read_text(encoding="utf-8")
    for service_name in (".AwgService", ".OpenVpnService", ".XrayService"):
        pattern = re.compile(
            r'        <service\n            android:name="' +
            re.escape(service_name) + r'"[\s\S]*?        </service>\n'
        )
        xml, count = pattern.subn("", xml, count=1)
        if count != 1:
            raise RuntimeError(f"Expected one Android VpnService component for {service_name}")
    if 'android.net.VpnService' in xml:
        raise RuntimeError("Platform VpnService component still registered")
    manifest_file.write_text(xml, encoding="utf-8")

    # Delete the permission-request path from the actual root-only Activity.
    activity_file = root / activity
    source = activity_file.read_text(encoding="utf-8")
    a = source.index("    private fun checkVpnPermission(")
    b = source.index("    private fun showOnVpnPermissionRejectDialog(", a)
    source = source[:a] + (
        "    private fun checkVpnPermission(onPermissionGranted: () -> Unit) {\n"
        "        onPermissionGranted()\n"
        "    }\n\n"
    ) + source[b:]
    if "VpnService.prepare(" in source:
        raise RuntimeError("Android VpnService permission request still present")
    activity_file.write_text(source, encoding="utf-8")

    print("Applied root-only AWG overlay; no Android VpnService components or mode toggle.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    parser.add_argument("--helper", required=True, type=Path)
    args = parser.parse_args()
    apply(args.upstream.resolve(), args.helper.resolve())
