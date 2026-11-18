#!/bin/sh
# Mocked Android iproute2/iptables tests. No real routing or root required.
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ROUTES="$ROOT_DIR/amnezia-root/root-routing.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
export MOCK_RULES="$TMP/rules" MOCK_LOG="$TMP/log"
: > "$MOCK_RULES"
: > "$MOCK_LOG"

cat > "$TMP/ip" <<'MOCK_IP'
#!/bin/sh
printf 'ip %s\n' "$*" >> "$MOCK_LOG"
if [ "${1:-}" = "-4" ] && [ "${2:-}" = "rule" ]; then
  case "${3:-}" in
    show) cat "$MOCK_RULES"; exit 0 ;;
    add) printf '%s: from all simulated lookup 2026\n' "$5" >> "$MOCK_RULES"; exit 0 ;;
    del)
      grep -v "^${5}:" "$MOCK_RULES" > "$MOCK_RULES.tmp" || :
      mv "$MOCK_RULES.tmp" "$MOCK_RULES"
      exit 0
      ;;
  esac
fi
if [ "$*" = "-4 route show table 2026" ]; then
  echo "default dev awg0 scope link"
fi
exit 0
MOCK_IP

cat > "$TMP/iptables" <<'MOCK_IPV4'
#!/bin/sh
printf 'iptables %s\n' "$*" >> "$MOCK_LOG"
# After vpn-on, the output jump is removed. Simulate -C not finding it.
case "$*" in *" -C OUTPUT "*) exit 1 ;; esac
exit 0
MOCK_IPV4

cat > "$TMP/ip6tables" <<'MOCK_IPV6'
#!/bin/sh
printf 'ip6tables %s\n' "$*" >> "$MOCK_LOG"
exit 0
MOCK_IPV6
chmod +x "$TMP/ip" "$TMP/iptables" "$TMP/ip6tables"
PATH="$TMP:$PATH"
export PATH

# Start has a protected underlay rule, a standard AWG rule, and a fallback.
sh "$ROUTES" start 10555 10.9.8.2/32 1.1.1.1
for priority in 12550 12551 20550 20551 27550 27551; do
  grep -q "^${priority}:" "$MOCK_RULES"
done
grep -q 'ip -4 rule add pref 12550 fwmark 0x20000/0x20000' "$MOCK_LOG"

# The second VPN must replace 205xx, but not the marked underlay or fallback.
sh "$ROUTES" vpn-on 10555
for priority in 12550 12551 27550 27551; do
  grep -q "^${priority}:" "$MOCK_RULES"
done
! grep -q '^20550:' "$MOCK_RULES"
! grep -q '^20551:' "$MOCK_RULES"
grep -q 'iptables -t nat -D OUTPUT -j AMN_ROOT_DNS' "$MOCK_LOG"

# Disconnecting the upper VPN restores ordinary AWG routing and DNS.
sh "$ROUTES" vpn-off 10555
grep -q '^20550:' "$MOCK_RULES"
grep -q '^20551:' "$MOCK_RULES"
grep -q 'iptables -t nat -I OUTPUT 1 -j AMN_ROOT_DNS' "$MOCK_LOG"

# Stop removes only our owned RPDB priorities, leaving unrelated rules.
echo '12000: system VPN rule' >> "$MOCK_RULES"
sh "$ROUTES" stop 10555
grep -q '^12000:' "$MOCK_RULES"
! grep -Eq '^(12550|12551|20550|20551|27550|27551):' "$MOCK_RULES"

# Fail closed on third-party collision rather than deleting another rule.
echo '12550: other application' >> "$MOCK_RULES"
if sh "$ROUTES" start 10555 10.9.8.2/32 1.1.1.1 >/dev/null 2>&1; then
  echo 'Unexpectedly overwrote existing RPDB priority' >&2
  exit 1
fi

# Fail closed with invalid UID.
if sh "$ROUTES" start 0 10.9.8.2/32 1.1.1.1 >/dev/null 2>&1; then
  echo 'Unexpectedly accepted system UID' >&2
  exit 1
fi
echo 'Root AWG Android VPN-over-VPN routing mocks: PASS'
