#!/usr/bin/env bash
# Writes /etc/nginx/conf.d/arvan-real-ip.conf from ArvanCloud's published edge addresses, so nginx takes the shopper's
# address from the ar-real-ip header only when the request really comes from an Arvan edge (LAUNCH.md, step 6.5).
# A request from anywhere else keeps its own address, whatever header it sends. Every rate limit and the app's
# per-address limits rest on this.
#
# Run as root, now and whenever Arvan adds ranges (the launch check warns when the list is older than 30 days).
# The old file stays when the download or `nginx -t` fails.
set -euo pipefail

URL="${ARVAN_IPS_URL:-https://www.arvancloud.ir/fa/ips.txt}"
TARGET="${ARVAN_REAL_IP_CONF:-/etc/nginx/conf.d/arvan-real-ip.conf}"

die() { echo "arvan-real-ip: $*" >&2; exit 1; }

list="$(mktemp)"
next="$(mktemp)"
trap 'rm -f "$list" "$next"' EXIT

curl -fsS --max-time 30 "$URL" -o "$list" || die "could not download $URL"

ipv4='^([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]{1,2}$'
ipv6='^[0-9A-Fa-f:]+/[0-9]{1,3}$'
count=0
{
    echo "# Written by tools/ops/arvan-real-ip.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ) from $URL; don't edit by hand."
    while IFS= read -r line || [ -n "$line" ]; do
        cidr="$(printf '%s' "$line" | tr -d '[:space:]')"
        [ -z "$cidr" ] && continue
        [[ "$cidr" =~ $ipv4 || "$cidr" =~ $ipv6 ]] || die "unexpected line in the list: '$cidr'"
        echo "set_real_ip_from $cidr;"
        count=$((count + 1))
    done < "$list"
    echo "real_ip_header ar-real-ip;"
    echo "real_ip_recursive off;"
} > "$next"

# A short list means the page changed, not that Arvan shrank.
[ "$count" -ge 5 ] || die "only $count ranges in $URL; nothing changed"

backup=""
if [ -f "$TARGET" ]; then
    backup="$(mktemp)"
    cp "$TARGET" "$backup"
fi
install -m 644 "$next" "$TARGET"

if nginx -t 2>/dev/null; then
    systemctl reload nginx
    echo "arvan-real-ip: $count ranges written to $TARGET, nginx reloaded"
else
    if [ -n "$backup" ]; then cp "$backup" "$TARGET"; else rm -f "$TARGET"; fi
    nginx -t || true
    die "nginx -t failed with the new list; the previous file is back"
fi
[ -n "$backup" ] && rm -f "$backup"
exit 0
