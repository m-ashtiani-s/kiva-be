#!/usr/bin/env bash
# The launch checklist, as far as a machine can check it (LAUNCH.md, step 6.9). As root on the server:
#   launch-check.sh                 every check; exit 1 when one fails
#   launch-check.sh --rate-limit    also sends 20 POSTs to /api/v1/orders in a row and wants a 429 among them
# Prints one line per check: ok, FAIL (blocks the launch) or warn (look at it). Secrets are only tested for being
# there, never printed; the personal-data scan prints a count, never a line. What only a person can check is listed
# at the end.
set -uo pipefail

ENV_FILE="${KIVA_ENV_FILE:-/opt/kiva/conf/kiva.env}"
SERVICE="${KIVA_SERVICE:-kiva}"
MEDIA_ROOT_DEFAULT="/var/lib/kiva/media"
BACKUP_ROOT="${KIVA_BACKUP_ROOT:-/root/backups}"

fails=0
warns=0
ok() { printf '  ok    %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; fails=$((fails + 1)); }
warn() { printf '  warn  %s\n' "$*"; warns=$((warns + 1)); }
check() { if eval "$2"; then ok "$1"; else fail "$1${3:+: $3}"; fi; }
section() { printf '\n%s\n' "$*"; }

env_value() {
    local line value
    line="$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
    value="${line#*=}"
    [[ "$value" == \"*\" ]] && value="${value:1:${#value}-2}"
    printf '%s' "$value"
}
has_env() { grep -qE "^$1=.+" "$ENV_FILE" 2>/dev/null; }

API="$(env_value KIVA_API_PUBLIC_URL)"; API="${API:-https://api.kivashop.ir}"
SITE="$(env_value KIVA_SITE_URL)"; SITE="${SITE:-https://kivashop.ir}"
ADMIN="https://admin.${SITE#https://}"
MEDIA_ROOT="$(env_value KIVA_MEDIA_ROOT)"; MEDIA_ROOT="${MEDIA_ROOT:-$MEDIA_ROOT_DEFAULT}"

# ---------------------------------------------------------------------------------------------------------------------
section "kiva.env ($ENV_FILE)"
if [ -f "$ENV_FILE" ]; then
    check "root, 600" '[ "$(stat -c %U:%a "$ENV_FILE")" = "root:600" ]' "is $(stat -c %U:%a "$ENV_FILE")"
else
    fail "the file is missing"
fi
check "jobs on" '[ "$(env_value KIVA_JOBS_ENABLED)" != "false" ]'
check "seed off" '[ "$(env_value KIVA_SEED_ENABLED)" != "true" ]'
origins="$(env_value KIVA_ALLOWED_ORIGINS)"
check "allowed origins have no localhost" '[ -n "$origins" ] && ! grep -qE "localhost|127\.0\.0\.1" <<< "$origins"' "$origins"
check "allowed origins have the panel" 'grep -q "$ADMIN" <<< "$origins"' "$origins"
check "behind one proxy (KIVA_TRUSTED_PROXY_HOPS=1)" '[ "$(env_value KIVA_TRUSTED_PROXY_HOPS)" = "1" ]'
check "payment: Zarinpal" '[ "$(env_value KIVA_PAYMENT_PROVIDER)" = "zarinpal" ]'
check "payment: real money (KIVA_ZARINPAL_SANDBOX=false)" '[ "$(env_value KIVA_ZARINPAL_SANDBOX)" = "false" ]'
check "payment: merchant id is set" 'has_env KIVA_ZARINPAL_MERCHANT_ID'
check "payment: callback on $API" '[ "$(env_value KIVA_API_PUBLIC_URL)" = "$API" ] && [[ "$API" == https://* ]]'
check "payment: result page on $SITE" '[[ "$(env_value KIVA_PAYMENT_RESULT_URL)" == "$SITE/"* ]]'
check "SMS: Faraz" '[ "$(env_value KIVA_SMS_PROVIDER)" = "faraz" ] && has_env KIVA_FARAZ_API_KEY'
for pattern in CODE PHONE_CHANGED ORDER_PAID PHOTOS_SENT ORDER_SHIPPED ORDER_DELIVERED ORDER_CANCELLED REFUND_COMPLETED CHANGE_RESOLVED ALERT; do
    check "SMS pattern KIVA_FARAZ_${pattern}_PATTERN" "has_env KIVA_FARAZ_${pattern}_PATTERN"
done
check "alert phone (KIVA_ALERT_PHONE)" '[[ "$(env_value KIVA_ALERT_PHONE)" =~ ^09[0-9]{9}$ ]]'
check "no bootstrap admin left (KIVA_BOOTSTRAP_ADMIN_PHONE)" '! has_env KIVA_BOOTSTRAP_ADMIN_PHONE'
check "media served by nginx (KIVA_MEDIA_SERVE_LOCALLY=false)" '[ "$(env_value KIVA_MEDIA_SERVE_LOCALLY)" = "false" ]'
check "media at $API/media" '[ "$(env_value KIVA_MEDIA_PUBLIC_URL)" = "$API/media" ]'

# ---------------------------------------------------------------------------------------------------------------------
section "the app"
check "$SERVICE is running" 'systemctl is-active --quiet "$SERVICE"'
check "/health is UP" 'curl -fsS --max-time 10 http://127.0.0.1:8080/health | grep -q "\"status\":\"UP\""'
started="$(systemctl show -p ActiveEnterTimestamp --value "$SERVICE" 2>/dev/null)"
if [ -n "$started" ]; then
    since_start="$(journalctl -u "$SERVICE" --since "$started" -o cat --no-pager -q 2>/dev/null)"
    check "since the start: no SMS pattern missing" '! grep -q "no pattern for" <<< "$since_start"' \
        "$(grep -o 'KIVA_FARAZ_[A-Z_]*_PATTERN' <<< "$since_start" | sort -u | tr '\n' ' ')"
    check "since the start: the payment gateway isn't 'fake'" '! grep -q "Payment gateway is .fake." <<< "$since_start"'
    check "since the start: the SMS provider isn't 'log'" '! grep -q "SMS provider is .log." <<< "$since_start"'
fi
phones="$(journalctl -u "$SERVICE" --since "-7 days" -o cat --no-pager -q 2>/dev/null | grep -cE '(^|[^0-9])09[0-9]{9}([^0-9]|$)')"
check "no whole phone number in 7 days of log" '[ "${phones:-0}" -eq 0 ]' \
    "$phones lines; see them with: journalctl -u $SERVICE --since -7days | grep -nE '(^|[^0-9])09[0-9]{9}([^0-9]|\$)'"

# ---------------------------------------------------------------------------------------------------------------------
section "the API from outside ($API)"
for slug in terms privacy returns about; do
    check "page $slug" '[ "$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$API/api/v1/pages/$slug")" = "200" ]'
done
config="$(curl -s --max-time 15 "$API/api/v1/config")"
check "/config has the eNamad badge" 'grep -q "\"ENAMAD\"" <<< "$config"'
error_headers="$(curl -s -D - -o /dev/null --max-time 15 "$API/api/v1/pages/launch-check-no-such-page")"
check "an error carries X-Trace-Id" 'grep -qi "^x-trace-id:" <<< "$error_headers"'
preflight() {
    curl -s -D - -o /dev/null --max-time 15 -X OPTIONS "$API/api/v1/cart" \
        -H "Origin: $1" -H "Access-Control-Request-Method: GET" | grep -i "^access-control-allow-origin:" || true
}
check "CORS lets the panel in" '[ -n "$(preflight "$ADMIN")" ]'
check "CORS keeps localhost out" '[ -z "$(preflight "http://localhost:3200")" ]'
media_file="$(find "$MEDIA_ROOT" -type f -name '*.webp' -print -quit 2>/dev/null)"
if [ -n "$media_file" ]; then
    media_url="$API/media/${media_file#"$MEDIA_ROOT"/}"
    check "media is cached for good (immutable)" 'curl -sI --max-time 15 "$media_url" | grep -qi "^cache-control:.*immutable"' "$media_url"
else
    warn "no photo in $MEDIA_ROOT yet to check the media headers with"
fi
if [ "${1:-}" = "--rate-limit" ]; then
    codes="$(for _ in $(seq 1 20); do curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 -X POST "$API/api/v1/orders"; done)"
    check "20 orders in a row from one address get a 429" 'grep -q "^429$" <<< "$codes"' "$(sort <<< "$codes" | uniq -c | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------------------------------------------------
section "the server"
latest="$(ls -1d "$BACKUP_ROOT"/????-??-?? 2>/dev/null | tail -n 1)"
check "backup of today or yesterday" '[ -n "$latest" ] && [[ "$(basename "$latest")" > "$(TZ=Asia/Tehran date -d "-2 days" +%F)" ]]' "latest: ${latest:-none}"
check "kiva-backup.timer on" 'systemctl is-enabled --quiet kiva-backup.timer && systemctl is-active --quiet kiva-backup.timer'
check "kiva-alerts.timer on" 'systemctl is-enabled --quiet kiva-alerts.timer && systemctl is-active --quiet kiva-alerts.timer'
check "media folder kiva:www-data, not world-readable" '[ "$(stat -c %U:%G "$MEDIA_ROOT" 2>/dev/null)" = "kiva:www-data" ] && [ "$(( 0$(stat -c %a "$MEDIA_ROOT") & 07 ))" -eq 0 ]'
real_ip="/etc/nginx/conf.d/arvan-real-ip.conf"
check "Arvan's addresses for the real IP" '[ -f "$real_ip" ]'
[ -f "$real_ip" ] && [ -n "$(find "$real_ip" -mtime +30)" ] && warn "$real_ip is over 30 days old: run arvan-real-ip.sh"
check "nginx -t" 'nginx -t >/dev/null 2>&1'
check "ufw active" 'ufw status | grep -q "Status: active"'
extra="$(ufw status | awk 'NR > 4 && / ALLOW / && $1 !~ /^(22|80|443)(\/tcp)?$/ { print $1 }' | sort -u | tr '\n' ' ')"
check "ufw allows only 22, 80 and 443" '[ -z "$extra" ]' "also $extra"
sshd_settings="$(sshd -T 2>/dev/null)"
check "SSH: no password login" 'grep -qx "passwordauthentication no" <<< "$sshd_settings"'
check "SSH: root by key only" 'grep -qE "^permitrootlogin (prohibit-password|without-password|no)$" <<< "$sshd_settings"'
check "PostgreSQL only on localhost" '! ss -ltnH "sport = :5432" | grep -vqE "127\.0\.0\.1:|\[::1\]:"'
for domain in "${SITE#https://}" "${API#https://}" "${ADMIN#https://}"; do
    cert="/etc/letsencrypt/live/$domain/fullchain.pem"
    if [ -f "$cert" ]; then
        check "certificate of $domain good for 14 more days" 'openssl x509 -checkend $((14 * 86400)) -noout -in "$cert" >/dev/null'
    else
        warn "no certificate at $cert (another path, or the CDN's own)"
    fi
done

# ---------------------------------------------------------------------------------------------------------------------
section "for a person to tick (LAUNCH.md, step 6.9)"
cat <<'EOF'
  [ ] a real purchase: paid by card, photo from the panel, «همونه!» in the shop, shipped, guest tracking, delivered
  [ ] a second purchase cancelled, its refund recorded with the bank's real reference number
  [ ] the restore test's line is in the work log (restore-test.sh)
  [ ] an alert SMS arrived (step 6.8)
  [ ] every Faraz pattern is approved in its panel, and the old API key is replaced
  [ ] terms, privacy, returns and about read right, and match the shop's rules (cancel, phone refund, 7-day return, photo)
  [ ] the shop and the panel are on their latest build
EOF

printf '\n%d failed, %d to look at\n' "$fails" "$warns"
[ "$fails" -eq 0 ]
