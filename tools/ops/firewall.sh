#!/usr/bin/env bash
# The firewall (LAUNCH.md, step 6.5): only SSH, 80 and 443 come in; everything goes out. As root.
# SSH is allowed before ufw is enabled, on whatever port sshd really listens on, so this can't lock the session out.
# Any other allow rule already there is listed, not removed: `ufw status numbered` and `ufw delete <n>` once checked.
set -euo pipefail

command -v ufw >/dev/null || { echo "firewall: ufw isn't installed (apt install ufw)" >&2; exit 1; }

ssh_port="$(sshd -T 2>/dev/null | awk '$1 == "port" { print $2; exit }')"
ssh_port="${ssh_port:-22}"
[ "$ssh_port" = "22" ] || echo "firewall: sshd listens on $ssh_port, not 22; that port is the one allowed"

ufw allow "$ssh_port/tcp" comment 'ssh'
ufw allow 80/tcp comment 'http (certbot, redirect)'
ufw allow 443/tcp comment 'https'
ufw default deny incoming
ufw default allow outgoing
ufw --force enable

others="$(ufw status | awk -v ssh="$ssh_port" 'NR > 4 && / ALLOW / && $1 !~ ("^(" ssh "|80|443)(/tcp)?$") { print }')"
if [ -n "$others" ]; then
    echo "firewall: other allow rules are still there; remove them if nothing needs them:"
    echo "$others"
fi
ufw status verbose
