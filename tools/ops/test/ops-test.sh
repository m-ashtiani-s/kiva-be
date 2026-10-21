#!/usr/bin/env bash
# The tests of tools/ops: every script run on Linux against stub commands (systemctl, journalctl, pg_dump, psql, pm2,
# ufw, nginx, curl as the gateway and the SMS service, ...), its good path and its failures. Nothing outside a
# temporary folder is touched, and no server, database or network is needed. From a Windows checkout:
#   wsl bash tools/ops/test/ops-test.sh
# The last line counts; exit 1 when one fails. Where systemd-analyze exists, the timers and units are checked too.
set -uo pipefail
OPS="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
pass=0; failn=0
ok() { echo "PASS $*"; pass=$((pass + 1)); }
bad() { echo "FAIL $*"; failn=$((failn + 1)); }
expect() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

STUB="$T/stub"; mkdir -p "$STUB"
stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$STUB/$1"; chmod +x "$STUB/$1"; }

# Each script, copied with LF only (as install-ops.sh does).
mkdir -p "$T/ops"
for f in "$OPS"/*.sh "$OPS"/*.sql; do tr -d '\r' < "$f" > "$T/ops/$(basename "$f")"; done

echo "== syntax"
for f in "$T"/ops/*.sh; do
    if bash -n "$f"; then ok "bash -n $(basename "$f")"; else bad "bash -n $(basename "$f")"; fi
done
expect "no CRLF in the repo copies" '! grep -rlq $'"'"'\r'"'"' "$OPS"/*.sh "$OPS"/nginx "$OPS"/systemd "$OPS"/ssh "$OPS"/*.sql "$OPS"/kiva.env.example'

# ---------------------------------------------------------------------------------------------------------------------
echo "== deploy-remote.sh"
H="$T/deploy"; mkdir -p "$H"
export KIVA_HOME="$H/opt/kiva" KIVA_DEPLOY_BACKUP_DIR="$H/backups" KIVA_DEPLOY_LOCK="$H/lock" KIVA_HEALTH_TIMEOUT=4
mkdir -p "$KIVA_HOME/conf" "$KIVA_HOME/bin" "$KIVA_HOME/lib"
echo "KIVA_X=1" > "$KIVA_HOME/conf/kiva.env"
echo "echo OLD GOOD" > "$KIVA_HOME/bin/kiva-be"
stub runuser 'while [ "$1" != "--" ]; do shift; done; shift; exec "$@"'
stub pg_dump 'echo DUMP'
stub chown 'exit 0'
stub systemctl 'echo "systemctl $*" >> "'"$H"'/systemctl.log"'
stub journalctl 'echo "(journal)"'
# /health is UP exactly when the running release's bin/kiva-be says GOOD.
stub curl 'grep -q GOOD "'"$KIVA_HOME"'/bin/kiva-be" 2>/dev/null && echo "{\"status\":\"UP\",\"database\":\"UP\"}" && exit 0; exit 22'
release() { # release <dir> <bin content>
    rm -rf "$1"; mkdir -p "$1/bin" "$1/lib"; echo "$2" > "$1/bin/kiva-be"; echo "commit=test" > "$1/kiva-release.txt"
    tar -czf "$1.tgz" -C "$1" .
}
release "$H/good" "echo NEW GOOD"
PATH="$STUB:$PATH" bash "$T/ops/deploy-remote.sh" "$H/good.tgz" > "$H/out1" 2>&1
status=$?
expect "a good release: exit 0" '[ $status -eq 0 ]'
expect "a good release is live" 'grep -q "NEW GOOD" "$KIVA_HOME/bin/kiva-be"'
expect "the old one is kept in .prev" 'grep -q "OLD GOOD" "$KIVA_HOME.prev/bin/kiva-be"'
expect "conf copied into the new release" '[ -f "$KIVA_HOME/conf/kiva.env" ]'
expect "a dump was made" '[ "$(ls "$KIVA_DEPLOY_BACKUP_DIR"/*.dump | wc -l)" -eq 1 ]'
expect "the tarball is removed after success" '[ ! -f "$H/good.tgz" ]'
release "$H/broken" "exec java"
PATH="$STUB:$PATH" bash "$T/ops/deploy-remote.sh" "$H/broken.tgz" > "$H/out2" 2>&1
status=$?
expect "a broken release: exit 1" '[ $status -eq 1 ]'
expect "after a broken release the previous one is live again" 'grep -q "NEW GOOD" "$KIVA_HOME/bin/kiva-be"'
expect "the broken one is kept in .failed" 'grep -q "exec java" "$KIVA_HOME.failed/bin/kiva-be"'
expect "it says the previous one is UP again" 'grep -q "previous one is UP again" "$H/out2"'
for i in 1 2 3 4 5 6; do release "$H/r$i" "echo NEW GOOD $i"; PATH="$STUB:$PATH" bash "$T/ops/deploy-remote.sh" "$H/r$i.tgz" > /dev/null 2>&1; sleep 1; done
expect "only the last 5 deploy dumps are kept" '[ "$(ls "$KIVA_DEPLOY_BACKUP_DIR"/*.dump | wc -l)" -eq 5 ]'
rm -f "$KIVA_HOME/conf/kiva.env"
release "$H/noconf" "echo GOOD"
PATH="$STUB:$PATH" bash "$T/ops/deploy-remote.sh" "$H/noconf.tgz" > "$H/out3" 2>&1
expect "no kiva.env: refused before anything" '[ $? -ne 0 ] && grep -q "kiva.env is missing" "$H/out3" && grep -q "GOOD 6" "$KIVA_HOME/bin/kiva-be"'
unset KIVA_HOME KIVA_DEPLOY_BACKUP_DIR KIVA_DEPLOY_LOCK KIVA_HEALTH_TIMEOUT

# ---------------------------------------------------------------------------------------------------------------------
echo "== deploy-fe-remote.sh"
F="$T/fe"; mkdir -p "$F/root/app"
export KIVA_FE_ROOT="$F/root" KIVA_FE_USER="$(id -un)" FE_HEALTH_TIMEOUT=4
echo "mode=standalone" > "$F/root/app/kiva-release.txt"; echo "OLD GOOD" > "$F/root/app/server.js"
stub pm2 'echo "pm2 $*" >> "'"$F"'/pm2.log"'
stub curl 'grep -q GOOD "'"$F"'/root/app/server.js" 2>/dev/null && exit 0; exit 7'
stub npm 'echo "npm $*" >> "'"$F"'/npm.log"'
fe_release() { rm -rf "$1"; mkdir -p "$1"; printf 'app=panel\nmode=%s\ncommit=x\n' "$2" > "$1/kiva-release.txt"; echo "$3" > "$1/server.js"; tar -czf "$1.tgz" -C "$1" .; }
fe_release "$F/good" standalone "NEW GOOD"
PATH="$STUB:$PATH" bash "$T/ops/deploy-fe-remote.sh" panel "$F/good.tgz" > "$F/out1" 2>&1
expect "fe: a good release goes live" '[ $? -eq 0 ] && grep -q "NEW GOOD" "$F/root/app/server.js" && grep -q "OLD GOOD" "$F/root/app.prev/server.js"'
expect "fe: ecosystem names server.js, port 3200, 127.0.0.1" 'grep -q "script: \"server.js\"" "$F/root/ecosystem.config.cjs" && grep -q "PORT: \"3200\"" "$F/root/ecosystem.config.cjs" && grep -q "HOSTNAME: \"127.0.0.1\"" "$F/root/ecosystem.config.cjs"'
expect "fe: pm2 startOrRestart and save" 'grep -q "startOrRestart" "$F/pm2.log" && grep -q "pm2 save" "$F/pm2.log"'
fe_release "$F/broken" standalone "BROKEN"
PATH="$STUB:$PATH" bash "$T/ops/deploy-fe-remote.sh" panel "$F/broken.tgz" > "$F/out2" 2>&1
expect "fe: a broken release fails and the previous is back" '[ $? -eq 1 ] && grep -q "NEW GOOD" "$F/root/app/server.js" && grep -q BROKEN "$F/root/app.failed/server.js"'
fe_release "$F/npm" npm "NPM GOOD"
PATH="$STUB:$PATH" bash "$T/ops/deploy-fe-remote.sh" shop "$F/npm.tgz" > "$F/out3" 2>&1
expect "fe: npm mode runs npm ci --omit=dev and next start on 3100" '[ $? -eq 0 ] && grep -q "ci --omit=dev" "$F/npm.log" && grep -q "start -p 3100 -H 127.0.0.1" "$F/root/ecosystem.config.cjs"'
unset KIVA_FE_ROOT KIVA_FE_USER FE_HEALTH_TIMEOUT

# ---------------------------------------------------------------------------------------------------------------------
echo "== backup.sh and restore-test.sh"
B="$T/backup"; mkdir -p "$B/media/2026/10/med_x"
echo photo > "$B/media/2026/10/med_x/480.webp"
export KIVA_BACKUP_ROOT="$B/backups" KIVA_MEDIA_ROOT="$B/media" PGHOST=stub
stub pg_dump 'echo "DUMP of $*"'
stub pg_restore 'cat > /dev/null'
stub createdb 'echo "createdb $*" >> "'"$B"'/pg.log"'
stub dropdb 'echo "dropdb $*" >> "'"$B"'/pg.log"'
stub psql 'for a in "$@"; do case "$a" in *flyway*) echo 31; exit 0;; esac; done; echo 3'
stub rclone 'echo "rclone $*" >> "'"$B"'/pg.log"'
mkdir -p "$KIVA_BACKUP_ROOT/2020-01-01" "$KIVA_BACKUP_ROOT/2099-12-31.partial"
old_kept="$(TZ=Asia/Tehran date -d '-13 days' +%F)"; mkdir -p "$KIVA_BACKUP_ROOT/$old_kept"
PATH="$STUB:$PATH" bash "$T/ops/backup.sh" > "$B/out1" 2>&1
status=$?
today="$(TZ=Asia/Tehran date +%F)"
expect "backup: exit 0" '[ $status -eq 0 ]'
expect "backup: today's folder with dump, media and sums" '[ -s "$KIVA_BACKUP_ROOT/$today/kiva.dump" ] && [ -s "$KIVA_BACKUP_ROOT/$today/media.tgz" ] && [ -s "$KIVA_BACKUP_ROOT/$today/SHA256SUMS" ]'
expect "backup: the media is in the tar" 'tar -tzf "$KIVA_BACKUP_ROOT/$today/media.tgz" | grep -q "media/2026/10/med_x/480.webp"'
expect "backup: older than 14 days removed, day 13 kept, partials removed" '[ ! -d "$KIVA_BACKUP_ROOT/2020-01-01" ] && [ -d "$KIVA_BACKUP_ROOT/$old_kept" ] && [ ! -d "$KIVA_BACKUP_ROOT/2099-12-31.partial" ]'
expect "backup: the folder is private" '[ "$(stat -c %a "$KIVA_BACKUP_ROOT/$today")" = "700" ]'
mkdir -p "$T/etc"; echo 'KIVA_BACKUP_RCLONE_REMOTE=arvan:kiva' > "$T/etc/backup.env"
sed "s#/etc/kiva/backup.env#$T/etc/backup.env#g" "$T/ops/backup.sh" > "$T/ops/backup-rclone.sh"
PATH="$STUB:$PATH" bash "$T/ops/backup-rclone.sh" > /dev/null 2>&1
expect "backup: with an rclone remote the day is copied there" 'grep -q "rclone copy .*$today arvan:kiva/$today" "$B/pg.log"'
PATH="$STUB:$PATH" bash "$T/ops/restore-test.sh" > "$B/out2" 2>&1
status=$?
expect "restore-test: exit 0 with the OK line" '[ $status -eq 0 ] && grep -q "restore-test: OK $today" "$B/out2"'
expect "restore-test: test database made and dropped" 'grep -q "createdb kiva_restore_test" "$B/pg.log" && grep -q "dropdb kiva_restore_test" "$B/pg.log"'
echo corrupted >> "$KIVA_BACKUP_ROOT/$today/kiva.dump"
PATH="$STUB:$PATH" bash "$T/ops/restore-test.sh" > "$B/out3" 2>&1
expect "restore-test: a changed dump is refused" '[ $? -ne 0 ] && grep -q "checksum" "$B/out3"'
KIVA_RESTORE_TEST_DB=kiva PATH="$STUB:$PATH" bash "$T/ops/restore-test.sh" > "$B/out4" 2>&1
expect "restore-test: refuses a test database not ending with _test" '[ $? -ne 0 ] && grep -q "must end with _test" "$B/out4"'
unset KIVA_BACKUP_ROOT KIVA_MEDIA_ROOT PGHOST

# ---------------------------------------------------------------------------------------------------------------------
echo "== purge-seed.sh"
P="$T/purge"; mkdir -p "$P"
stub systemctl 'case "$*" in *is-active*) exit ${ACTIVE:-3};; esac'
stub psql 'cat > "'"$P"'/psql.in"'
stub pg_dump 'echo DUMP'
PGHOST=stub PATH="$STUB:$PATH" bash "$T/ops/purge-seed.sh" > "$P/out1" 2>&1
expect "purge: dry run ends with ROLLBACK" '[ $? -eq 0 ] && head -n 1 "$P/psql.in" | grep -qx "BEGIN;" && tail -n 1 "$P/psql.in" | grep -qx "ROLLBACK;"'
PGHOST=stub KIVA_BACKUP_ROOT="$P/b" PATH="$STUB:$PATH" bash "$T/ops/purge-seed.sh" --apply > "$P/out2" 2>&1
expect "purge: --apply dumps first and ends with COMMIT" '[ $? -eq 0 ] && tail -n 1 "$P/psql.in" | grep -qx "COMMIT;" && ls "$P"/b/before-purge-*.dump >/dev/null'
ACTIVE=0 PGHOST=stub PATH="$STUB:$PATH" bash "$T/ops/purge-seed.sh" --apply > "$P/out3" 2>&1
expect "purge: refused while kiva runs" '[ $? -ne 0 ] && grep -q "is running" "$P/out3"'

# ---------------------------------------------------------------------------------------------------------------------
echo "== set-env.sh"
E="$T/env"; mkdir -p "$E"; printf 'KIVA_A=1\nKIVA_B=old\n# a comment\n' > "$E/kiva.env"; chmod 600 "$E/kiva.env"
export KIVA_ENV_FILE="$E/kiva.env"
printf 'new\n' | bash "$T/ops/set-env.sh" KIVA_B > /dev/null
expect "set-env: a line is replaced where it is" '[ "$(sed -n 2p "$E/kiva.env")" = "KIVA_B=new" ]'
printf 's3cr$t "x"\ns3cr$t "x"\n' | bash "$T/ops/set-env.sh" --secret KIVA_SECRET > "$E/out" 2>&1
expect "set-env: a secret with spaces and quotes is quoted for systemd" 'grep -qxF "KIVA_SECRET=\"s3cr\$t \\\"x\\\"\"" "$E/kiva.env"'
expect "set-env: the secret never shows" '! grep -q "s3cr" "$E/out"'
printf 'a\nb\n' | bash "$T/ops/set-env.sh" --secret KIVA_C > /dev/null 2>&1
expect "set-env: two different entries change nothing" '[ $? -ne 0 ] && ! grep -q KIVA_C "$E/kiva.env"'
bash "$T/ops/set-env.sh" --unset KIVA_A > /dev/null
expect "set-env: --unset removes the line, the comment stays" '! grep -q "^KIVA_A=" "$E/kiva.env" && grep -q "# a comment" "$E/kiva.env"'
expect "set-env: the file stays 600 and the previous one is kept" '[ "$(stat -c %a "$E/kiva.env")" = "600" ] && [ -f "$E/kiva.env.previous" ]'
expect "set-env: a bad name is refused" '! bash "$T/ops/set-env.sh" "kiva_lower" < /dev/null > /dev/null 2>&1'
unset KIVA_ENV_FILE

# ---------------------------------------------------------------------------------------------------------------------
echo "== alerts.sh"
A="$T/alerts"; mkdir -p "$A/state" "$A/backups/$(TZ=Asia/Tehran date +%F)"
cat > "$A/kiva.env" <<'EOF'
KIVA_FARAZ_API_KEY="key with space"
KIVA_FARAZ_ALERT_PATTERN=alertPat
KIVA_ALERT_PHONE=09120000000
EOF
export KIVA_ENV_FILE="$A/kiva.env" KIVA_ALERTS_STATE="$A/state" KIVA_BACKUP_ROOT="$A/backups"
esc=$'\e'
cat > "$A/journal" <<EOF
12:00:00.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.a.u.p.PaymentConfirmation - PAID_BUT_ORDER_NOT_PAYABLE: the gateway didn't give payment pay_1 back
12:00:01.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.i.j.JobScheduler - Job expire-unpaid-orders failed; it runs again at its next time
12:00:02.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.i.j.JobScheduler - Job release-reservations failed; it runs again at its next time
12:00:03.000 [x] [t] ${esc}[31mWARN ${esc}[0;39m c.k.a.u.p.ReconcilePaymentUc - ReconcilePaymentUc: payment pay_2 has been VERIFYING since 2026-10-21T08:00:00Z; the gateway is asked again
12:00:04.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.i.c.h.StatusPages - Unhandled error on GET /api/v1/x
12:00:05.000 [x] [t] ${esc}[34mINFO ${esc}[0;39m c.k.Foo - nothing ERROR-ish here
EOF
stub journalctl 'cat "'"$A"'/journal"; : > "'"$A"'/journal"; for a in "$@"; do case "$a" in --cursor-file=*) echo c > "${a#--cursor-file=}";; esac; done'
stub systemctl 'exit 1'
stub curl 'for a in "$@"; do case "$a" in *health*) echo "{\"status\":\"UP\"}"; exit 0;; @*) cat "${a#@}" >> "'"$A"'/headers";; esac; done; printf "%s\n" "$@" >> "'"$A"'/sent"; echo "{\"status\":\"success\"}"'
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" > "$A/out1" 2>&1
expect "alerts: exit 0" '[ $? -eq 0 ]'
expect "alerts: PAID_NOT_PAYABLE sent" 'grep -q "\"what\":\"PAID_NOT_PAYABLE\"" "$A/sent"'
expect "alerts: two job failures are one SMS with x2" 'grep -q "\"what\":\"JOB_FAILED x2\"" "$A/sent"'
expect "alerts: VERIFYING_STUCK from the WARN" 'grep -q "\"what\":\"VERIFYING_STUCK\"" "$A/sent"'
expect "alerts: the other ERROR is ERROR" 'grep -q "\"what\":\"ERROR\"" "$A/sent"'
expect "alerts: four SMS, nothing for INFO, health or the backup" '[ "$(grep -c "\"what\"" "$A/sent")" -eq 4 ]'
expect "alerts: the API key goes in a header, quotes removed" 'grep -qx "Api-Key: key with space" "$A/headers" && ! grep -q "key with space" "$A/sent"'
expect "alerts: no log text in any SMS" '! grep -q "pay_1\|expire-unpaid" "$A/sent"'
: > "$A/sent"
echo "12:10:00.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.i.j.JobScheduler - Job expire-unpaid-orders failed; it runs again" > "$A/journal"
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" > "$A/out2" 2>&1
expect "alerts: the same kind again within 15 minutes is not sent" '[ ! -s "$A/sent" ] && grep -q "JOB_FAILED x1: already sent" "$A/out2"'
echo 0 > "$A/state/last-JOB_FAILED"
echo "12:30:00.000 [x] [t] ${esc}[1;31mERROR${esc}[0;39m c.k.i.j.JobScheduler - Job expire-unpaid-orders failed; it runs again" > "$A/journal"
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" > /dev/null 2>&1
expect "alerts: after the wait it is sent again" 'grep -q "\"what\":\"JOB_FAILED\"" "$A/sent"'
: > "$A/sent"; rm -rf "$A/backups"/*; rm -f "$A/state"/last-*
stub curl 'for a in "$@"; do case "$a" in *health*) exit 7;; esac; done; printf "%s\n" "$@" >> "'"$A"'/sent"; echo "{\"status\":\"success\"}"'
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" > /dev/null 2>&1
expect "alerts: health down and no backup are sent" 'grep -q "\"what\":\"HEALTH_DOWN\"" "$A/sent" && grep -q "\"what\":\"BACKUP_OLD\"" "$A/sent"'
stub curl 'for a in "$@"; do case "$a" in *health*) exit 7;; esac; done; echo "{\"status\":\"error\"}"'
rm -f "$A/state"/last-*
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" > "$A/out3" 2>&1
expect "alerts: Faraz refusing is exit 1 and nothing is marked sent" '[ $? -eq 1 ] && [ ! -f "$A/state/last-HEALTH_DOWN" ]'
stub curl 'printf "%s\n" "$@" >> "'"$A"'/sent"; echo "{\"status\":\"success\"}"'
: > "$A/sent"; echo "12:40:00.000 [x] [t] ERROR c.k.Foo - must not be read by --test" > "$A/journal"
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" --test > "$A/out4" 2>&1
expect "alerts: --test sends TEST at once and reads no log" '[ $? -eq 0 ] && grep -q "\"what\":\"TEST\"" "$A/sent" && [ "$(grep -c "\"what\"" "$A/sent")" -eq 1 ] && [ -s "$A/journal" ]'
PATH="$STUB:$PATH" bash "$T/ops/alerts.sh" --test > /dev/null 2>&1
expect "alerts: --test is never held back" '[ "$(grep -c "\"what\":\"TEST\"" "$A/sent")" -eq 2 ]'
unset KIVA_ENV_FILE KIVA_ALERTS_STATE KIVA_BACKUP_ROOT

# ---------------------------------------------------------------------------------------------------------------------
echo "== arvan-real-ip.sh"
R="$T/arvan"; mkdir -p "$R"
printf '185.143.232.0/22\n188.229.116.16/30\n94.101.182.0/27\n2.144.3.128/28\n37.32.16.0/27\n2a0b:4d80::/32\n' > "$R/ips.txt"
stub curl 'out=""; prev=""; for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done; cp "'"$R"'/ips.txt" "$out"'
stub nginx 'exit "${NGINX_T:-0}"'
stub systemctl 'echo "systemctl $*" >> "'"$R"'/log"'
export ARVAN_REAL_IP_CONF="$R/arvan-real-ip.conf"
PATH="$STUB:$PATH" bash "$T/ops/arvan-real-ip.sh" > "$R/out1" 2>&1
expect "arvan: 6 ranges, ar-real-ip, nginx reloaded" '[ $? -eq 0 ] && [ "$(grep -c set_real_ip_from "$R/arvan-real-ip.conf")" -eq 6 ] && grep -qx "real_ip_header ar-real-ip;" "$R/arvan-real-ip.conf" && grep -q reload "$R/log"'
cp "$R/arvan-real-ip.conf" "$R/before"
printf '185.143.232.0/22\n10.0.0.0/8\n1.1.1.0/24\n2.2.2.0/24\n3.3.3.0/24\n4.4.4.0/24\n' > "$R/ips.txt"
NGINX_T=1 PATH="$STUB:$PATH" bash "$T/ops/arvan-real-ip.sh" > "$R/out2" 2>&1
expect "arvan: nginx -t failing puts the old file back" '[ $? -ne 0 ] && cmp -s "$R/before" "$R/arvan-real-ip.conf"'
printf '<html>moved</html>\n' > "$R/ips.txt"
PATH="$STUB:$PATH" bash "$T/ops/arvan-real-ip.sh" > "$R/out3" 2>&1
expect "arvan: a page that isn't a list changes nothing" '[ $? -ne 0 ] && cmp -s "$R/before" "$R/arvan-real-ip.conf"'
unset ARVAN_REAL_IP_CONF

# ---------------------------------------------------------------------------------------------------------------------
echo "== firewall.sh"
W="$T/fw"; mkdir -p "$W"
stub sshd 'echo "port 22"'
stub ufw 'echo "ufw $*" >> "'"$W"'/log"; if [ "$1" = status ] && [ $# -eq 1 ]; then printf "Status: active\n\nTo                         Action      From\n--                         ------      ----\n22/tcp                     ALLOW       Anywhere\n80/tcp                     ALLOW       Anywhere\n8080                       ALLOW       Anywhere\n22/tcp (v6)                ALLOW       Anywhere (v6)\n"; fi'
PATH="$STUB:$PATH" bash "$T/ops/firewall.sh" > "$W/out" 2>&1
expect "firewall: ssh allowed before enable" '[ "$(grep -n "allow 22/tcp" "$W/log" | cut -d: -f1)" -lt "$(grep -n "enable" "$W/log" | cut -d: -f1)" ]'
expect "firewall: the stray 8080 rule is named, not removed" 'grep -q "^8080 " "$W/out" && ! grep -q "delete" "$W/log"'
expect "firewall: v6 twin of 22 is not named" '! grep -q "^22/tcp (v6)" "$W/out"'

# ---------------------------------------------------------------------------------------------------------------------
echo "== launch-check.sh (smoke: everything missing must fail, not crash)"
stub systemctl 'exit 3'; stub journalctl 'exit 0'; stub curl 'exit 7'; stub ufw 'echo "Status: inactive"'; stub sshd 'echo "passwordauthentication yes"'
stub ss 'exit 0'; stub nginx 'exit 1'; stub openssl 'exit 1'
KIVA_ENV_FILE="$T/none.env" PATH="$STUB:$PATH" bash "$T/ops/launch-check.sh" > "$T/lc.out" 2>&1
status=$?
expect "launch-check: exit 1 with a count line" '[ $status -eq 1 ] && grep -qE "^[0-9]+ failed, [0-9]+ to look at$" "$T/lc.out"'
expect "launch-check: no bash error in its output" '! grep -qE "syntax error|unbound variable|command not found|integer expression" "$T/lc.out"'
expect "launch-check: the person's list is there" 'grep -q "a real purchase" "$T/lc.out"'

# ---------------------------------------------------------------------------------------------------------------------
echo "== install-ops.sh"
I="$T/install"; mkdir -p "$I/src"; cp -r "$OPS"/. "$I/src/"
printf 'echo crlf\r\n' >> "$I/src/backup.sh"
stub systemctl 'exit 0'; stub chown 'exit 0'
KIVA_OPS_DIR="$I/opt" KIVA_UNIT_DIR="$I/units" PATH="$STUB:$PATH" bash "$I/src/install-ops.sh" > "$I/out" 2>&1
expect "install: scripts 700, sql 600, units 644" '[ "$(stat -c %a "$I/opt/backup.sh")" = 700 ] && [ "$(stat -c %a "$I/opt/purge_seed.sql")" = 600 ] && [ "$(stat -c %a "$I/units/kiva-alerts.timer")" = 644 ]'
expect "install: CRLF stripped" '! grep -q $'"'"'\r'"'"' "$I/opt/backup.sh"'
expect "install: nginx, ssh and the reference unit copied" '[ -f "$I/opt/nginx/api.kivashop.ir.conf" ] && [ -f "$I/opt/ssh/00-kiva.conf" ] && [ -f "$I/opt/systemd/kiva.service" ]'

# ---------------------------------------------------------------------------------------------------------------------
if command -v systemd-analyze >/dev/null; then
    echo "== systemd units"
    expect "the backup runs at 03:00 Tehran" 'systemd-analyze calendar "*-*-* 03:00:00 Asia/Tehran" | grep -q "03:00:00 +0330"'
    U="$T/units"; mkdir -p "$U"
    for u in "$OPS"/systemd/*.service "$OPS"/systemd/*.timer; do tr -d '\r' < "$u" > "$U/$(basename "$u")"; done
    # The ExecStart paths only exist on the server; anything else systemd-analyze says is a mistake in a unit.
    complaints="$(systemd-analyze verify "$U"/*.service "$U"/*.timer 2>&1 | grep -vE 'is not executable|No such file|Failed to prepare filename|not found' || true)"
    expect "every unit verifies" '[ -z "$complaints" ]'
    [ -z "$complaints" ] || echo "$complaints"
fi

echo
echo "$pass passed, $failn failed"
[ "$failn" -eq 0 ]
