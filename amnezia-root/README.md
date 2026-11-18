# Amnezia Android root-only AWG preview (experimental)

This directory is an **experimental source overlay** against
[amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client), branch `dev`.
The original GitHub repository is read-only for this account. This overlay deliberately
lives in a separate branch of the user's existing `Index` repository and does not
modify the unrelated Windows Lightscreen project on `main`.

## What this fork does

- **Root only**: no user toggle and **no registered Android VpnService**. It
  refuses to connect if the user does not grant `su`; it never falls back
  to Android's single VPN slot.
- AWG/WireGuard through a separate ordinary foreground service, root-created
  `awg0` TUN descriptor and upstream Go backend (AWG 3.1 config serialization).
- Binds the AWG service process to physical Wi-Fi/Ethernet/cellular transport
  so another Android VPN cannot capture the AWG UDP transport.
- Android VPN-over-AWG support: protected upper-VPN transport sockets
  (Android's fwmark bit `0x20000`) are routed through AWG at priority 12550,
  before the system's physical-network rules. Before a second VPN exists,
  priorities 20550/20551 carry ordinary traffic over AWG. Once Android
  announces a VPN `NetworkCapabilities.TRANSPORT_VPN`, these early rules
  are removed so Android can route app traffic into the second VPN. The
  27550/27551 fallback remains, and rollback restores the early rules
  when the upper VPN disappears. None of this requires a user toggle.
- Regular DNS requests are redirected only when the upper VPN is absent.
  Protected transport DNS is never rewritten. IPv6 is currently blocked
  to avoid leaking out of the IPv4-only AWG tunnel.
- UID-excluding IPv4 policy routing, managed DNS OUTPUT chain, IPv6 leak
  blocking and root-only rollback. Every start failure invokes cleanup.
- On disconnect/service destruction, cleans our IP rules, iptables chains,
  route table and interface, including rules from the previous prototype.
- Exposes Root TUN failure details by toast and notification, rather than
  only Qt's uninformative ErrorCode 1000.

**Not implemented:** Root Xray TProxy, OpenVPN/Cloak underlay, IPv6 dual-stack,
complete split-tunneling, and ROM-dependent SELinux compatibility.

**Validation:** CI runs mocked RPDB rules tests for normal AWG,
second-VPN arrival, second-VPN loss, cleanup, and conflict refusal.
A passing build or mocked test is **not** evidence of working double VPN
on a physical Samsung Galaxy. Please verify both VPN handshakes and
the browser's public IP after enabling the second VPN. Kernel/ROM netd
priorities and VPN implementations vary; apps without an IPv4-capable
server can fail because this preview deliberately blocks IPv6.
Test on a backed-up rooted ARM64 phone; do not treat this as production-ready.

## Upstream source changed by overlay

- `client/android/src/org/amnezia/vpn/AmneziaActivity.kt`
- `client/android/wireguard/src/main/kotlin/org/amnezia/vpn/protocol/wireguard/Wireguard.kt`
- `client/android/AndroidManifest.xml`
- Added `RootMode.kt`, `RootAwgService.kt`, `RootTun.kt`
- Asset `amnezia-root-routing.sh` and the ARM64 root TUN helper

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

## Emergency recovery after the earlier toggle build

The old opt-in build could leave IPv4 priority 5000 and IPv6 chains active
after toggling OFF. The new start/stop script actively cleans the previous
build's rules. If connectivity is still broken, use a root shell to inspect
`ip -4 rule show`, `ip -4 route show table 2026`,
`iptables -t nat -S AMN_ROOT_DNS` and
`ip6tables -S AMN_ROOT_V6` before removing anything.
Never flush all iptables tables: those are managed by Android/netd and
other VPN applications.

The GitHub preview signing key is regenerated per workflow run. Back up the
server configuration before uninstalling an older preview to install an APK
signed with a different key.

## Android VPN-over-VPN diagnosis

The earlier prototype installed ip rules at priorities `5000/5001` for
every UID other than Amnezia, overriding Android's own VPN routing.
This is why a browser could use AWG but an upper VPN could not reliably
take over as the active system VPN.

The current underlay rules are designed for modern Android:

- `12550/12551`: only fwmark `0x20000/0x20000` (*protectedFromVpn*) from
  **application UIDs**, excluding Amnezia's UID and Android system DNS
  resolver UIDs. This returns an upper VPN provider's transport to AWG
  without hijacking netd's own protected DNS sockets.
- `20550/20551`: ordinary app traffic when no second Android VPN is active.
- `27550/27551`: fallback for traffic after Android VPN rules.
  In stacking mode, 20550/20551 are removed so the **upper** VPN's VpnService
  owns ordinary app traffic, while AWG still handles its transport.

The service watches VPN transport networks and updates these rules automatically.
A second VPN must still be able to reach its own endpoint using IPv4;
not all VPN clients or OEM firmware will honor the expected fwmarks.
The root script does not disable Android's VPN permission checks or modify
the second app. It cleans up both the previous 5000-series and the new
rules when Amnezia disconnects.

To diagnose a failure, collect `ip -4 rule show`,
`ip -4 route show table 2026` and the second VPN's error log. Do not
share any VPN profile or private key.
