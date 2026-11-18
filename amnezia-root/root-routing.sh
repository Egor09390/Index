#!/system/bin/sh
# Root AWG underlay. All global network rules must roll back on failure/stop.
set -eu
PATH=/system/bin:/system/xbin:/vendor/bin:/data/local/tmp:$PATH
IFACE=awg0
TABLE=2026
DNS_CHAIN=AMN_ROOT_DNS
V6_CHAIN=AMN_ROOT_V6
ACTION="${1:-}"
UID_VALUE="${2:-}"
ADDRESS="${3:-}"
DNS="${4:-}"

# Android fwmark protectedFromVpn is bit 17 (0x20000). A second Android VPN
# marks its transport sockets via VpnService.protect(). Route these protected
# UNDERLAY sockets through AWG before Android chooses the physical network.
# Android's own VPN rule then takes precedence for ordinary app traffic.
PROTECT_MARK=0x20000/0x20000
P_PROTECTED_LOW=12550
P_PROTECTED_HIGH=12551
P_EARLY_LOW=20550
P_EARLY_HIGH=20551
P_FALLBACK_LOW=27550
P_FALLBACK_HIGH=27551

case "$UID_VALUE" in ''|*[!0-9]*) echo "Invalid app UID" >&2; exit 2 ;; esac
[ "$UID_VALUE" -ge 10000 ] || { echo "Refusing system UID" >&2; exit 2; }
UID_BELOW=$((UID_VALUE-1))
UID_ABOVE=$((UID_VALUE+1))

cleanup() {
  # Only our owned chains, interface, table and exact RPDB rules.
  # Remove the layer-aware rules before dropping our route table.
  for pref in "$P_PROTECTED_LOW" "$P_PROTECTED_HIGH" \
              "$P_EARLY_LOW" "$P_EARLY_HIGH" \
              "$P_FALLBACK_LOW" "$P_FALLBACK_HIGH"; do
    ip -4 rule del pref "$pref" lookup "$TABLE" 2>/dev/null || :
  done
  iptables -t nat -D OUTPUT -j "$DNS_CHAIN" 2>/dev/null || :
  iptables -t nat -F "$DNS_CHAIN" 2>/dev/null || :
  iptables -t nat -X "$DNS_CHAIN" 2>/dev/null || :
  ip6tables -D OUTPUT -j "$V6_CHAIN" 2>/dev/null || :
  ip6tables -F "$V6_CHAIN" 2>/dev/null || :
  ip6tables -X "$V6_CHAIN" 2>/dev/null || :
  ip -4 rule del pref 5000 uidrange 0-"$UID_BELOW" lookup "$TABLE" 2>/dev/null || :
  ip -4 rule del pref 5001 uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE" 2>/dev/null || :
  # Also remove our previous experimental build's global rule.
  ip -4 rule del pref 5000 lookup "$TABLE" 2>/dev/null || :
  # An APK reinstall can change the app UID, so purge the previous version's
  # 5001 rule by our owned priority+table rather than the new UID range.
  ip -4 rule del pref 5001 lookup "$TABLE" 2>/dev/null || :
  ip -4 rule del pref 100 uidrange "$UID_VALUE"-"$UID_VALUE" lookup main 2>/dev/null || :
  ip -4 route flush table "$TABLE" 2>/dev/null || :
  ip link del "$IFACE" 2>/dev/null || :
  ip -4 route flush cache 2>/dev/null || :
}

is_ipv4() {
  echo "$1" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$' || return 1
  oldifs="$IFS"; IFS=.
  set -- $1
  IFS="$oldifs"
  for n in "$@"; do [ "$n" -le 255 ] || return 1; done
}

# With no Android VpnService present, priority 20550 must route ordinary
# sockets through AWG ahead of Android's implicit physical-network rules.
# When a second VPN is present, remove 20550/20551 so Android's VPN rules
# can win. The protected VPN provider sockets still use our 12550/12551
# rules, allowing VPN-over-AWG without a platform VPN slot for Amnezia.
sync_layered() {
  ip -4 route show table "$TABLE" | grep -q "default dev $IFACE" || {
    echo "Root AWG route not installed; refusing layer switch" >&2
    exit 1
  }
  case "$ACTION" in
    vpn-on)
      # Fallback rules must be present before removing the early ones.
      ip -4 rule show | grep -Eq "^[[:space:]]*${P_FALLBACK_LOW}:" || {
        echo "Root AWG fallback route missing; refusing to bypass early rules" >&2
        exit 1
      }
      ip -4 rule show | grep -Eq "^[[:space:]]*${P_FALLBACK_HIGH}:" || {
        echo "Root AWG fallback route missing; refusing to bypass early rules" >&2
        exit 1
      }
      ip -4 rule del pref "$P_EARLY_LOW" lookup "$TABLE" 2>/dev/null || :
      ip -4 rule del pref "$P_EARLY_HIGH" lookup "$TABLE" 2>/dev/null || :
      # Let Android's second-VPN DNS take priority over the AWG DNS rewrite.
      iptables -t nat -D OUTPUT -j "$DNS_CHAIN" 2>/dev/null || :
      ;;
    vpn-off)
      if ! ip -4 rule show | grep -Eq "^[[:space:]]*${P_EARLY_LOW}:"; then
        ip -4 rule add pref "$P_EARLY_LOW" uidrange 0-"$UID_BELOW" lookup "$TABLE"
      fi
      if ! ip -4 rule show | grep -Eq "^[[:space:]]*${P_EARLY_HIGH}:"; then
        ip -4 rule add pref "$P_EARLY_HIGH" uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE"
      fi
      # Restore ordinary DNS rewrite only after restoring the early rules.
      if iptables -t nat -L "$DNS_CHAIN" -n >/dev/null 2>&1; then
        iptables -t nat -C OUTPUT -j "$DNS_CHAIN" >/dev/null 2>&1 ||
          iptables -t nat -I OUTPUT 1 -j "$DNS_CHAIN"
      fi
      ;;
  esac
  ip -4 route flush cache 2>/dev/null || :
}

case "$ACTION" in
  vpn-on|vpn-off)
    sync_layered
    ;;
  stop)
    cleanup
    ;;
  refresh)
    ip -4 route flush cache 2>/dev/null || :
    ;;
  start)
    case "$ADDRESS" in
      */*) IP="${ADDRESS%/*}"; PREFIX="${ADDRESS#*/}" ;;
      *) echo "Missing IPv4 CIDR" >&2; exit 2 ;;
    esac
    is_ipv4 "$IP" || { echo "Invalid client IPv4" >&2; exit 2; }
    case "$PREFIX" in ''|*[!0-9]*) echo "Invalid prefix" >&2; exit 2 ;; esac
    [ "$PREFIX" -ge 1 ] && [ "$PREFIX" -le 32 ] || exit 2
    [ -z "$DNS" ] || is_ipv4 "$DNS" || { echo "Invalid DNS IPv4" >&2; exit 2; }

    ip link show "$IFACE" >/dev/null
    if ip -4 rule show | grep -Eq '^[[:space:]]*(12550|12551|20550|20551|27550|27551):'; then
      echo "Root VPN policy priorities 12550/20550/27550 already in use" >&2
      exit 1
    fi
    trap 'cleanup' EXIT HUP INT TERM
    ip addr replace "$ADDRESS" dev "$IFACE"
    ip link set dev "$IFACE" mtu 1280 up

    # Fail closed for IPv6 until a real IPv6 underlay implementation exists.
    ip6tables -N "$V6_CHAIN"
    ip6tables -A "$V6_CHAIN" -m owner ! --uid-owner "$UID_VALUE" -j REJECT
    ip6tables -I OUTPUT 1 -j "$V6_CHAIN"

    ip -4 route replace default dev "$IFACE" table "$TABLE"
    # Do not intercept AWG's own UID. Let Android netd's fwmark and the
    # process-bound *physical* network select its transport route. This
    # avoids the prior 'lookup main' problem on Android.
    # Protected sockets of the second VPN must use AWG even though netd
    # marks them as bypassing Android's VpnService. This is the underlay.
    # Only Android application UIDs may be upper-VPN providers. Excluding
    # system UIDs matters: netd/DnsResolver also use protected fwmarks,
    # and redirecting those would break the upper VPN's DNS network.
    if [ "$UID_BELOW" -ge 10000 ]; then
      ip -4 rule add pref "$P_PROTECTED_LOW" fwmark "$PROTECT_MARK" uidrange 10000-"$UID_BELOW" lookup "$TABLE"
    fi
    ip -4 rule add pref "$P_PROTECTED_HIGH" fwmark "$PROTECT_MARK" uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE"

    # After Android's VPN rules, provide fallback for explicitly excluded UIDs.
    # These do not override an established second VPN's routing policy.
    ip -4 rule add pref "$P_FALLBACK_LOW" uidrange 0-"$UID_BELOW" lookup "$TABLE"
    ip -4 rule add pref "$P_FALLBACK_HIGH" uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE"

    # While there is no second VPN, Android uses implicit physical fwmarks.
    # Intercept before that point; remove these two rules on VPN onAvailable.
    ip -4 rule add pref "$P_EARLY_LOW" uidrange 0-"$UID_BELOW" lookup "$TABLE"
    ip -4 rule add pref "$P_EARLY_HIGH" uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE"

    if [ -n "$DNS" ]; then
      iptables -t nat -N "$DNS_CHAIN"
      iptables -t nat -A "$DNS_CHAIN" -p udp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -m mark ! --mark "$PROTECT_MARK" -j DNAT --to-destination "$DNS"
      iptables -t nat -A "$DNS_CHAIN" -p tcp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -m mark ! --mark "$PROTECT_MARK" -j DNAT --to-destination "$DNS"
      iptables -t nat -I OUTPUT 1 -j "$DNS_CHAIN"
    fi
    trap - EXIT HUP INT TERM
    ;;
  *) echo "Usage: $0 start|stop|refresh|vpn-on|vpn-off <app-uid> [ipv4/cidr] [dns-ipv4]" >&2; exit 2 ;;
esac
