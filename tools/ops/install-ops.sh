#!/usr/bin/env bash
# Puts tools/ops on the server (LAUNCH.md, «پیش از گام‌ها»). From a Windows checkout:
#   scp -r tools/ops kiva:/tmp/kiva-ops
#   ssh kiva 'bash /tmp/kiva-ops/install-ops.sh'
# Scripts go to /opt/kiva-ops (root only), the backup and alert units to /etc/systemd/system, and the nginx, SSH and
# kiva.service files to /opt/kiva-ops for the steps to compare and copy. Nothing is enabled, started or reloaded here:
# each step does its own. Running it again updates the files; a CRLF from a Windows copy is stripped.
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="${KIVA_OPS_DIR:-/opt/kiva-ops}"
UNITS="${KIVA_UNIT_DIR:-/etc/systemd/system}"

put() { # put <source> <target> <mode>
    local next="$2.new"
    tr -d '\r' < "$1" > "$next"
    chmod "$3" "$next"
    chown root:root "$next" 2>/dev/null || true
    mv "$next" "$2"
}

install -d -m 700 "$DEST" "$DEST/nginx" "$DEST/ssh" "$DEST/systemd"
install -d -m 755 "$UNITS"

shopt -s nullglob
for script in "$SRC"/*.sh; do
    [ "$(basename "$script")" = "install-ops.sh" ] && continue
    put "$script" "$DEST/$(basename "$script")" 700
done
for file in "$SRC"/*.sql "$SRC"/kiva.env.example; do put "$file" "$DEST/$(basename "$file")" 600; done
for file in "$SRC"/nginx/*.conf; do put "$file" "$DEST/nginx/$(basename "$file")" 600; done
for file in "$SRC"/ssh/*.conf; do put "$file" "$DEST/ssh/$(basename "$file")" 600; done
# kiva.service is the reference to compare the live unit with (step 6.4); the timers' units are installed for real.
for file in "$SRC"/systemd/kiva.service; do put "$file" "$DEST/systemd/kiva.service" 600; done
for unit in "$SRC"/systemd/kiva-*.service "$SRC"/systemd/kiva-*.timer; do put "$unit" "$UNITS/$(basename "$unit")" 644; done
command -v systemctl >/dev/null && systemctl daemon-reload

echo "install-ops: $DEST and the timers' units are up to date. Enable them in their steps:"
echo "  6.3  systemctl enable --now kiva-backup.timer"
echo "  6.8  systemctl enable --now kiva-alerts.timer"
