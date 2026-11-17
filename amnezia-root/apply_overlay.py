#!/usr/bin/env python3
"""Apply the root-AWG experimental overlay to a clean amnezia-vpn/amnezia-client dev checkout.

Fails closed if upstream changes an anchor; never silently patches the wrong release.
"""
import argparse
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
        if (RootMode.isEnabled()) RootAwgService::class.java else proto.serviceClass

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
        RootTun.cleanup(context)
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
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />''')
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

    header = "client/ui/controllers/settingsUiController.h"
    edit(root, header,
         '''    void toggleAmneziaDns(bool enable);''',
         '''    bool isRootModeEnabled();
    void toggleRootMode(bool enabled);

    void toggleAmneziaDns(bool enable);''')
    cpp = "client/ui/controllers/settingsUiController.cpp"
    edit(root, cpp,
         '''#ifdef Q_OS_ANDROID
    #include "platforms/android/android_controller.h"
#endif''',
         '''#ifdef Q_OS_ANDROID
    #include "platforms/android/android_controller.h"
    #include <QJniObject>
#endif''')
    edit(root, cpp,
         '''void SettingsUiController::toggleAmneziaDns(bool enable)
{''',
         '''bool SettingsUiController::isRootModeEnabled()
{
#ifdef Q_OS_ANDROID
    return QJniObject::callStaticMethod<jboolean>(
        "org/amnezia/vpn/RootMode", "isEnabled", "()Z");
#else
    return false;
#endif
}

void SettingsUiController::toggleRootMode(bool enabled)
{
#ifdef Q_OS_ANDROID
    QJniObject::callStaticMethod<jboolean>(
        "org/amnezia/vpn/RootMode", "setEnabled", "(Z)Z",
        static_cast<jboolean>(enabled));
#else
    Q_UNUSED(enabled);
#endif
}

void SettingsUiController::toggleAmneziaDns(bool enable)
{''')

    qml = "client/ui/qml/Pages2/PageSettingsConnection.qml"
    edit(root, qml,
         '''                ListItemType {
                    id: amneziaDnsRow''',
         '''                ListItemType {
                    id: rootModeRow
                    Layout.fillWidth: true
                    visible: Qt.platform.os === "android"
                    text: qsTr("Root Mode (AWG / bypass Android VPN)")
                    subtitle: rootModeSwitch.checked
                        ? qsTr("ON: root TUN without Android VPN slot. Disconnect before changing modes.")
                        : qsTr("OFF: standard Android VPN. A second VPN replaces this connection.")
                    onClicked: {
                        rootModeSwitch.toggle()
                        rootModeSwitch.toggled()
                    }
                    SwitchType {
                        id: rootModeSwitch
                        focusPolicy: Qt.NoFocus
                        checked: SettingsController.isRootModeEnabled()
                        onToggled: {
                            if (checked !== SettingsController.isRootModeEnabled()) {
                                SettingsController.toggleRootMode(checked)
                            }
                            checked = SettingsController.isRootModeEnabled()
                        }
                    }
                }

                ListItemType {
                    id: amneziaDnsRow''')

    print("Applied root AWG overlay; upstream VpnService implementations are unchanged.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    parser.add_argument("--helper", required=True, type=Path)
    args = parser.parse_args()
    apply(args.upstream.resolve(), args.helper.resolve())
