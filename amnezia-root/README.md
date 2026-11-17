# Amnezia Android Root AWG 3.1 preview (NOT a production APK)

This directory is an **experimental source overlay** against
[amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client), branch `dev`.
The original GitHub repository is read-only for this account. This overlay deliberately
lives in a separate branch of the user's existing `Index` repository and does not
modify the unrelated Windows Lightscreen project on `main`.

## Supported scope

- Opt-in **Root Mode** switch in Android connection settings, stored in the existing
  encrypted Preferences store. Default is OFF. Enabling checks `su -c id -u`.
- Regular Android `Service` with `specialUse` foreground type, separate from
  the official `VpnService` classes, for **AWG / WireGuard only**.
- Root-created `awg0` TUN descriptor passed into the upstream Go backend via
  `SCM_RIGHTS` to preserve upstream AWG 3.1 protocol configuration serialization.
- IPv4 policy route in table 2026, explicit exclusion of the client UID,
  MTU 1280, TCP/UDP port-53 DNS DNAT, fail-closed IPv6 rejection,
  disconnect cleanup, and network-change route-cache refresh.
- No change to the standard VpnService flow when the switch is OFF.

**Not yet implemented:** Xray dokodemo-door TProxy interception,
OpenVPN/Cloak root support, seamless migration of existing VPN connections,
IPv6 dual-stack routing, complete split-tunneling semantics, device-tested
SELinux compatibility, and Android-specific DNS resolver behavior (DoT/DoH).

**Critical limitations:** the root path has not yet been compiled into a verified
APK or run on a rooted device. /dev/tun availability, SELinux restrictions,
`ip rule uidrange` support, `iptables` modules and access to the main route
table vary by Android ROM. Do not treat an Actions artifact as confirmed
working double-VPN support until verified on your device.

## Upstream files modified by apply_overlay.py

- `client/android/src/org/amnezia/vpn/AmneziaActivity.kt`
- `client/android/wireguard/src/main/kotlin/org/amnezia/vpn/protocol/wireguard/Wireguard.kt`
- `client/android/AndroidManifest.xml`
- `client/ui/controllers/settingsUiController.cpp`
- `client/ui/controllers/settingsUiController.h`
- `client/ui/qml/Pages2/PageSettingsConnection.qml`

Added upstream files:

- `client/android/src/org/amnezia/vpn/RootMode.kt`
- `client/android/src/org/amnezia/vpn/RootAwgService.kt`
- `client/android/wireguard/src/main/kotlin/org/amnezia/vpn/protocol/wireguard/RootTun.kt`
- Build-generated `client/android/assets/amnezia-root-tun-arm64`
- `client/android/assets/amnezia-root-routing.sh`

## Manual build (Linux, Qt 6.10.3, Android NDK r27, SDK, CMake, Conan)

```bash
git clone --recursive -b dev https://github.com/amnezia-vpn/amnezia-client.git upstream
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.11718014"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang" \
  -O2 -fPIE -pie amnezia-root/root-tun-fd.c -o /tmp/amnezia-root-tun-arm64
python3 amnezia-root/apply_overlay.py upstream --helper /tmp/amnezia-root-tun-arm64
(cd upstream && QT_INSTALL_DIR=/path/to/Qt-install deploy/build.sh -t android --abi arm64-v8a)
```

The build is **not** simply a `./gradlew assembleRelease` operation:
the Qt/CMake and native Go components must be built first.

## Routing cautions

The existing proposal's `iptables -m owner` in `PREROUTING` is invalid
(owner matching is only available where socket ownership can be determined).
Comma-separated CIDRs after one `-d` are also invalid. This AWG preview
therefore uses IPv4 TUN policy routing, not a fake universal TProxy chain.
The root routing script checks for occupied policy-rule priorities rather
than silently replacing another application's rules. Use a full device
backup and test on a non-primary phone first.

## License

Upstream Amnezia VPN licenses remain applicable. This source preview contains
only original helper code and modifications; use upstream's build and license
notices when redistributing binaries.
