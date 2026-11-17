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

case "$UID_VALUE" in ''|*[!0-9]*) echo "Invalid app UID" >&2; exit 2 ;; esac
[ "$UID_VALUE" -ge 10000 ] || { echo "Refusing system UID" >&2; exit 2; }
UID_BELOW=$((UID_VALUE-1))
UID_ABOVE=$((UID_VALUE+1))

cleanup() {
  # Only our owned chains, interface, table and exact RPDB rules.
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
  ip -4 rule del pref 100 uidrange "$UID_VALUE"-"$UID_VALUE" lookup main 2>/dev/null || :
  ip -4 route flush table "$TABLE" 2>/dev/null || :
  ip link del "$IFACE" 2>/dev/null || :
  ip -4 route flush cache 2>/dev/null || :
}

is_ipv4() {
  echo "$1" | grep -Eq '^([0-9]{1,3}\\.){3}[0-9]{1,3}$' || return 1
  oldifs="$IFS"; IFS=.
  set -- $1
  IFS="$oldifs"
  for n in "$@"; do [ "$n" -le 255 ] || return 1; done
}

case "$ACTION" in
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
    if ip -4 rule show | grep -Eq '^[[:space:]]*(5000|5001):'; then
      echo "Network policy rule priorities 5000/5001 are already in use" >&2
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
    ip -4 rule add pref 5000 uidrange 0-"$UID_BELOW" lookup "$TABLE"
    ip -4 rule add pref 5001 uidrange "$UID_ABOVE"-4294967294 lookup "$TABLE"

    if [ -n "$DNS" ]; then
      iptables -t nat -N "$DNS_CHAIN"
      iptables -t nat -A "$DNS_CHAIN" -p udp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -j DNAT --to-destination "$DNS"
      iptables -t nat -A "$DNS_CHAIN" -p tcp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -j DNAT --to-destination "$DNS"
      iptables -t nat -I OUTPUT 1 -j "$DNS_CHAIN"
    fi
    trap - EXIT HUP INT TERM
    ;;
  *) echo "Usage: $0 start|stop|refresh <app-uid> [ipv4/cidr] [dns-ipv4]" >&2; exit 2 ;;
esac
