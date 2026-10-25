#!/usr/bin/env bash
# Texts the owner when something needs a person (LAUNCH.md, step 6.8). kiva-alerts.timer runs it every 5 minutes, as
# root. It reads what kiva logged since its last run (journalctl's cursor file, so no line is read twice or skipped),
# checks /health and the nightly backup, and sends one SMS per kind with the Faraz pattern KIVA_FARAZ_ALERT_PATTERN,
# whose only variable `what` is a fixed word below and a count: never a log line, which may hold a name or a phone.
#
#   urgent, at most once per KIVA_ALERTS_URGENT_EVERY (15 minutes) each:
#     PAID_NOT_PAYABLE   money taken for an order that couldn't take it (PaymentConfirmation)
#     AMOUNT_MISMATCH    the gateway says another amount was paid
#     GATEWAY_REJECTED   Zarinpal refused us: the merchant id, the domain or the IP is wrong
#     VERIFYING_STUCK    a payment has been VERIFYING for over 10 minutes
#     JOB_FAILED         a scheduled job threw
#     HEALTH_DOWN        /health isn't UP
#   at most once per KIVA_ALERTS_OTHER_EVERY (an hour) each:
#     ERROR              any other ERROR line
#     BACKUP_OLD         no backup folder of today or yesterday; or kiva-backup.service failed
#
# KIVA_ALERTS_DRY_RUN=1 prints what would be sent instead of sending it (a test, or a first look).
# `alerts.sh --test` sends one SMS with what=TEST right away and reads nothing: the pattern and the phone work.
set -euo pipefail

ENV_FILE="${KIVA_ENV_FILE:-/opt/kiva/conf/kiva.env}"
STATE_DIR="${KIVA_ALERTS_STATE:-/var/lib/kiva-alerts}"
SERVICE="${KIVA_SERVICE:-kiva}"
HEALTH_URL="${KIVA_HEALTH_URL:-http://127.0.0.1:8080/health}"
BACKUP_ROOT="${KIVA_BACKUP_ROOT:-/root/backups}"
URGENT_EVERY="${KIVA_ALERTS_URGENT_EVERY:-900}"
OTHER_EVERY="${KIVA_ALERTS_OTHER_EVERY:-3600}"
DRY_RUN="${KIVA_ALERTS_DRY_RUN:-}"

log() { echo "alerts: $*"; }

# A value of kiva.env, read as text: the file is never sourced, so nothing in it runs.
env_value() {
    local line value
    line="$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
    value="${line#*=}"
    if [[ "$value" == \"*\" ]]; then
        value="${value:1:${#value}-2}"
        value="${value//\\\"/\"}"
        value="${value//\\\\/\\}"
    fi
    printf '%s' "$value"
}

TEST_ONLY="no"
[ "${1:-}" = "--test" ] && TEST_ONLY="yes"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
now="$(date +%s)"

declare -A count=()
note() { count[$1]=$(( ${count[$1]:-0} + ${2:-1} )); }

# Everything that may need a person, counted by kind.
scan() {
    # --- the log since the last run (the first run looks back 6 minutes) ---
    local since=() lines line today yesterday
    [ -s "$STATE_DIR/cursor" ] || since=(--since "-6 min")
    lines="$(journalctl -u "$SERVICE" --cursor-file="$STATE_DIR/cursor" "${since[@]}" -o cat --no-pager -q 2>/dev/null \
        | sed 's/\x1b\[[0-9;]*m//g' || true)"

    while IFS= read -r line; do
        [ -n "$line" ] || continue
        case "$line" in
            *PAID_BUT_ORDER_NOT_PAYABLE*) note PAID_NOT_PAYABLE ;;
            *AMOUNT_MISMATCH* | *"the session was paid another amount"*) note AMOUNT_MISMATCH ;;
            *"Zarinpal "*": rejected "* | *"refused our confirmation"* | *"refused our inquiry"*) note GATEWAY_REJECTED ;;
            *"has been VERIFYING since"*) note VERIFYING_STUCK ;;
            *"Job "*" failed"* | *"Jobs: "*" failed"*) note JOB_FAILED ;;
            *" ERROR "*) note ERROR ;;
        esac
    done <<< "$lines"

    # --- the app answers ---
    if ! curl -fsS --max-time 10 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
        note HEALTH_DOWN
    fi

    # --- last night's backup is there ---
    today="$(TZ=Asia/Tehran date +%F)"
    yesterday="$(TZ=Asia/Tehran date -d '-1 day' +%F)"
    if [ ! -d "$BACKUP_ROOT/$today" ] && [ ! -d "$BACKUP_ROOT/$yesterday" ]; then
        note BACKUP_OLD
    elif systemctl is-failed --quiet kiva-backup.service 2>/dev/null; then
        note BACKUP_OLD
    fi
}

if [ "$TEST_ONLY" = "yes" ]; then note TEST; else scan; fi

[ "${#count[@]}" -gt 0 ] || exit 0

api_key="$(env_value KIVA_FARAZ_API_KEY)"
pattern="$(env_value KIVA_FARAZ_ALERT_PATTERN)"
phone="$(env_value KIVA_ALERT_PHONE)"
line_number="$(env_value KIVA_FARAZ_LINE_NUMBER)"
line_number="${line_number:-90008361}"
base_url="$(env_value KIVA_FARAZ_BASE_URL)"
base_url="${base_url:-https://api.iranpayamak.com}"

send() {
    local what="$1"
    if [ -n "$DRY_RUN" ]; then
        log "would send: $what"
        return 0
    fi
    if [ -z "$api_key" ] || [ -z "$pattern" ] || [ -z "$phone" ]; then
        log "can't send '$what': KIVA_FARAZ_API_KEY, KIVA_FARAZ_ALERT_PATTERN or KIVA_ALERT_PHONE is missing in $ENV_FILE" >&2
        return 1
    fi
    local body answer
    body="$(printf '{"code":"%s","attributes":{"what":"%s"},"recipient":"%s","line_number":"%s","number_format":"english"}' \
        "$pattern" "$what" "$phone" "$line_number")"
    # The key goes through a header file, so it never shows in `ps`.
    answer="$(curl -sS --max-time 20 -X POST "$base_url/ws/v1/sms/pattern" \
        -H @<(printf 'Api-Key: %s\n' "$api_key") \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        --data "$body" 2>&1 || true)"
    if printf '%s' "$answer" | grep -q '"status" *: *"success"'; then
        log "sent: $what"
        return 0
    fi
    log "Faraz didn't take '$what' (the next run tries again)" >&2
    return 1
}

failed=0
for key in "${!count[@]}"; do
    case "$key" in
        TEST) every=0 ;;
        ERROR | BACKUP_OLD) every="$OTHER_EVERY" ;;
        *) every="$URGENT_EVERY" ;;
    esac
    last="$(cat "$STATE_DIR/last-$key" 2>/dev/null || echo 0)"
    if [ $((now - last)) -lt "$every" ]; then
        log "$key x${count[$key]}: already sent $(( (now - last) / 60 )) min ago"
        continue
    fi
    what="$key"
    [ "${count[$key]}" -gt 1 ] && what="$key x${count[$key]}"
    if send "$what"; then
        echo "$now" > "$STATE_DIR/last-$key"
    else
        failed=1
    fi
done
exit "$failed"
