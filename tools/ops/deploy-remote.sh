#!/usr/bin/env bash
# The server's half of tools/ops/deploy.ps1 (LAUNCH.md, step 6.4). Run as root:
#   bash deploy-remote.sh /tmp/kiva-be-release.tgz
#
#   1. a quick pg_dump into /root/backups/deploy (the last 5 kept): the way back if a new migration breaks data
#   2. the release unpacked into /opt/kiva.new, with /opt/kiva/conf (kiva.env) copied in
#   3. stop kiva, /opt/kiva -> /opt/kiva.prev, /opt/kiva.new -> /opt/kiva, start kiva
#   4. wait for /health to say UP; not within KIVA_HEALTH_TIMEOUT (90) seconds: stop, put /opt/kiva.prev back,
#      start it, and fail. The broken release stays in /opt/kiva.failed.
# The previous release starts fine on a schema a newer release migrated: Flyway ignores migrations it doesn't know.
set -euo pipefail

TARBALL="${1:?usage: deploy-remote.sh <tarball>}"
KIVA_HOME="${KIVA_HOME:-/opt/kiva}"
SERVICE="${KIVA_SERVICE:-kiva}"
DB_NAME="${KIVA_DB_NAME:-kiva}"
HEALTH_URL="${KIVA_HEALTH_URL:-http://127.0.0.1:8080/health}"
HEALTH_TIMEOUT="${KIVA_HEALTH_TIMEOUT:-90}"
BACKUP_DIR="${KIVA_DEPLOY_BACKUP_DIR:-/root/backups/deploy}"
KEEP_DUMPS="${KIVA_DEPLOY_KEEP_DUMPS:-5}"
LOCK="${KIVA_DEPLOY_LOCK:-/run/kiva-deploy.lock}"

NEW="$KIVA_HOME.new"
PREV="$KIVA_HOME.prev"
FAILED="$KIVA_HOME.failed"

log() { echo "deploy: $*"; }
die() { echo "deploy: $*" >&2; exit 1; }

run_pg() {
    if [ -n "${PGHOST:-}" ] || [ "$(id -un)" = "postgres" ]; then "$@"; else runuser -u postgres -- "$@"; fi
}

healthy() {
    local waited=0
    while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
        if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then return 0; fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# Two deploys at once would swap each other's folders.
exec 9>"$LOCK"
flock -n 9 || die "another deploy is running"

[ -f "$TARBALL" ] || die "no release at $TARBALL"
[ -f "$KIVA_HOME/conf/kiva.env" ] || die "$KIVA_HOME/conf/kiva.env is missing; the release takes its configuration from there"

log "quick backup of $DB_NAME"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
dump="$BACKUP_DIR/$(date +%F-%H%M%S).dump"
(umask 077 && run_pg pg_dump -Fc "$DB_NAME" > "$dump") || die "pg_dump failed; nothing was changed"
ls -1t "$BACKUP_DIR"/*.dump | tail -n +$((KEEP_DUMPS + 1)) | xargs -r rm -f
log "  $dump ($(du -h "$dump" | cut -f1))"

log "unpacking into $NEW"
rm -rf "$NEW"
mkdir -p "$NEW"
tar -xzf "$TARBALL" -C "$NEW"
[ -x "$NEW/bin/kiva-be" ] || chmod +x "$NEW/bin/kiva-be" 2>/dev/null || die "the release has no bin/kiva-be"
[ -d "$NEW/lib" ] || die "the release has no lib/"
[ -f "$NEW/kiva-release.txt" ] && log "  release: $(tr '\n' ' ' < "$NEW/kiva-release.txt")"
# The code belongs to root and only the configuration is copied over: the app's user can't change what it runs.
chown -R root:root "$NEW"
chmod -R go-w "$NEW"
cp -a "$KIVA_HOME/conf" "$NEW/conf"

log "switching releases"
systemctl stop "$SERVICE"
rm -rf "$PREV" "$FAILED"
mv "$KIVA_HOME" "$PREV"
mv "$NEW" "$KIVA_HOME"
systemctl start "$SERVICE" || true

if healthy; then
    rm -f "$TARBALL"
    log "UP: $(curl -fsS --max-time 5 "$HEALTH_URL")"
    exit 0
fi

log "/health isn't UP after ${HEALTH_TIMEOUT}s; putting the previous release back"
journalctl -u "$SERVICE" -n 40 --no-pager || true
systemctl stop "$SERVICE" || true
mv "$KIVA_HOME" "$FAILED"
mv "$PREV" "$KIVA_HOME"
systemctl start "$SERVICE" || true
if healthy; then
    die "the new release didn't come up (kept in $FAILED); the previous one is UP again"
fi
die "neither the new release nor the previous one is UP; the dump before this deploy is $dump"
