#!/usr/bin/env bash
# Sets one variable of /opt/kiva/conf/kiva.env without the value going through a chat, git, a log or the shell's
# history (LAUNCH.md, step 6.6). As root:
#   set-env.sh --secret KIVA_ZARINPAL_MERCHANT_ID    the value is typed and not shown
#   set-env.sh KIVA_FARAZ_ORDER_PAID_PATTERN          the value is shown while typed (a pattern code isn't secret)
#   set-env.sh --unset KIVA_BOOTSTRAP_ADMIN_PHONE     removes the line
# The line is replaced where it is, or added at the end; the file stays root 600, and its previous version is kept as
# kiva.env.previous (600 too). Nothing restarts: `systemctl restart kiva` when all changes are in.
set -euo pipefail

ENV_FILE="${KIVA_ENV_FILE:-/opt/kiva/conf/kiva.env}"

mode="plain"
case "${1:-}" in
    --secret) mode="secret"; shift ;;
    --unset) mode="unset"; shift ;;
esac
name="${1:-}"
[[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "usage: set-env.sh [--secret|--unset] NAME" >&2; exit 2; }
[ -f "$ENV_FILE" ] || { echo "set-env: $ENV_FILE doesn't exist" >&2; exit 1; }

if [ "$mode" != "unset" ]; then
    if [ "$mode" = "secret" ]; then
        read -rsp "$name (not shown): " value; echo
        read -rsp "$name again: " again; echo
        [ "$value" = "$again" ] || { echo "set-env: the two don't match; nothing changed" >&2; exit 1; }
    else
        read -rp "$name: " value
    fi
    [ -n "$value" ] || { echo "set-env: empty; nothing changed (use --unset to remove it)" >&2; exit 1; }
    # systemd's EnvironmentFile: a bare value when it has nothing special, otherwise in double quotes with \ and " escaped.
    if [[ "$value" =~ ^[A-Za-z0-9_.,:/@+=%-]+$ ]]; then
        line="$name=$value"
    else
        escaped="${value//\\/\\\\}"
        escaped="${escaped//\"/\\\"}"
        line="$name=\"$escaped\""
    fi
fi

umask 077
next="$(mktemp "$(dirname "$ENV_FILE")/.kiva.env.XXXXXX")"
trap 'rm -f "$next"' EXIT
found="no"
while IFS= read -r current || [ -n "$current" ]; do
    if [[ "$current" == "$name="* ]]; then
        found="yes"
        [ "$mode" = "unset" ] || printf '%s\n' "$line"
    else
        printf '%s\n' "$current"
    fi
done < "$ENV_FILE" > "$next"
if [ "$found" = "no" ] && [ "$mode" != "unset" ]; then printf '%s\n' "$line" >> "$next"; fi

cp -p "$ENV_FILE" "$(dirname "$ENV_FILE")/kiva.env.previous"
chmod 600 "$next"
chown root:root "$next" 2>/dev/null || true
mv "$next" "$ENV_FILE"
trap - EXIT

case "$mode:$found" in
    unset:yes) echo "set-env: $name removed" ;;
    unset:no) echo "set-env: $name wasn't there" ;;
    *:yes) echo "set-env: $name changed" ;;
    *) echo "set-env: $name added" ;;
esac
echo "set-env: restart to use it: systemctl restart kiva"
