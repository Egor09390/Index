#!/system/bin/sh
# Root-only IPv4 AWG/WireGuard underlay. All changes are scoped and reversible.
set -eu
PATH=/system/bin:/system/xbin:/vendor/bin:/data/local/tmp:$PATH
IFACE=awg0
TABLE=2026
DNS_CHAIN=AMN_ROOT_DNS
V6_CHAIN=AMN_ROOT_V6
ACTION="\${1:-}"
UID_VALUE="\${2:-}"
ADDRESS="\${3:-}"
DNS="\${4:-}"

case "$UID_VALUE" in ''|*[!0-9]*) echo "Invalid UID" >&2; exit 2 ;; esac
[ "$UID_VALUE" -ge 10000 ] || { echo "Refusing system UID" >&2; exit 2; }

cleanup() {
  iptables -t nat -D OUTPUT -j "$DNS_CHAIN" 2>/dev/null || :
  iptables -t nat -F "$DNS_CHAIN" 2>/dev/null || :
  iptables -t nat -X "$DNS_CHAIN" 2>/dev/null || :
  ip6tables -D OUTPUT -j "$V6_CHAIN" 2>/dev/null || :
  ip6tables -F "$V6_CHAIN" 2>/dev/null || :
  ip6tables -X "$V6_CHAIN" 2>/dev/null || :
  ip -4 rule del pref 5000 lookup "$TABLE" 2>/dev/null || :
  ip -4 rule del pref 100 uidrange "$UID_VALUE"-"$UID_VALUE" lookup main 2>/dev/null || :
  ip -4 route flush table "$TABLE" 2>/dev/null || :
  ip link del "$IFACE" 2>/dev/null || :
}
is_ipv4() {
  echo "$1" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$' || return 1
  local oldifs="$IFS"; IFS=.
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
      */*) IP="\${ADDRESS%/*}"; PREFIX="\${ADDRESS#*/}" ;;
      *) echo "Missing CIDR" >&2; exit 2 ;;
    esac
    is_ipv4 "$IP" || { echo "Invalid client IPv4" >&2; exit 2; }
    case "$PREFIX" in ''|*[!0-9]*) exit 2 ;; esac
    [ "$PREFIX" -ge 1 ] && [ "$PREFIX" -le 32 ] || exit 2
    [ -z "$DNS" ] || is_ipv4 "$DNS" || { echo "Invalid DNS IPv4" >&2; exit 2; }

    ip link show "$IFACE" >/dev/null
    # Never seize an existing policy-rule priority from Android/netd or another app.
    if ip -4 rule show | grep -Eq '^100:|^5000:'; then
      echo "Policy rule priority occupied, refusing to replace" >&2
      exit 1
    fi
    ip addr replace "$ADDRESS" dev "$IFACE"
    ip link set dev "$IFACE" mtu 1280 up
    # Fail-safe: block IPv6 whenever this IPv4-only root mode is active.
    ip6tables -N "$V6_CHAIN"
    ip6tables -A "$V6_CHAIN" -m owner ! --uid-owner "$UID_VALUE" -j REJECT
    ip6tables -I OUTPUT 1 -j "$V6_CHAIN"

    ip -4 route replace default dev "$IFACE" table "$TABLE"
    # The WireGuard UDP socket belongs to this app UID. It must bypass the underlay route.
    ip -4 rule add pref 100 uidrange "$UID_VALUE"-"$UID_VALUE" lookup main
    ip -4 rule add pref 5000 lookup "$TABLE"

    if [ -n "$DNS" ]; then
      iptables -t nat -N "$DNS_CHAIN"
      iptables -t nat -A "$DNS_CHAIN" -p udp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -j DNAT --to-destination "$DNS"
      iptables -t nat -A "$DNS_CHAIN" -p tcp --dport 53 -m owner ! --uid-owner "$UID_VALUE" -j DNAT --to-destination "$DNS"
      iptables -t nat -I OUTPUT 1 -j "$DNS_CHAIN"
    fi
    ;;
  *)
    echo "Usage: $0 start|stop|refresh <app-uid> [ipv4/cidr] [dns-ipv4]" >&2
    exit 2
    ;;
esac
