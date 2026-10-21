#!/usr/bin/env bash
# The nightly backup (LAUNCH.md, step 6.3), run by kiva-backup.timer at 03:00 Tehran. As root:
#   <root>/<yyyy-mm-dd>/kiva.dump    pg_dump -Fc of the database, checked by reading its table of contents back
#   <root>/<yyyy-mm-dd>/media.tgz    the media folder
#   <root>/<yyyy-mm-dd>/SHA256SUMS   what restore-test.sh and pull-backup.ps1 check before trusting a copy
# A day's folder only appears once all three are complete, so a crash leaves a .partial folder and never a backup
# that looks whole. Days older than KIVA_BACKUP_KEEP_DAYS go. If /etc/kiva/backup.env names an rclone remote
# (KIVA_BACKUP_RCLONE_REMOTE), the day is copied there too; otherwise pull-backup.ps1 fetches it to a PC.
set -euo pipefail

[ -f /etc/kiva/backup.env ] && . /etc/kiva/backup.env

BACKUP_ROOT="${KIVA_BACKUP_ROOT:-/root/backups}"
DB_NAME="${KIVA_DB_NAME:-kiva}"
MEDIA_ROOT="${KIVA_MEDIA_ROOT:-/var/lib/kiva/media}"
KEEP_DAYS="${KIVA_BACKUP_KEEP_DAYS:-14}"
RCLONE_REMOTE="${KIVA_BACKUP_RCLONE_REMOTE:-}"

log() { echo "backup: $*"; }
die() { echo "backup: FAILED: $*" >&2; exit 1; }

# As the postgres superuser on the server; as whoever runs it when PGHOST points elsewhere (a test).
run_pg() {
    if [ -n "${PGHOST:-}" ] || [ "$(id -un)" = "postgres" ]; then "$@"; else runuser -u postgres -- "$@"; fi
}

human() { du -h "$1" | cut -f1; }

started=$(date +%s)
day="$(TZ=Asia/Tehran date +%F)"
dir="$BACKUP_ROOT/$day"
work="$dir.partial"

umask 077
mkdir -p "$BACKUP_ROOT"
rm -rf "$work"
mkdir -p "$work"

log "pg_dump $DB_NAME"
run_pg pg_dump -Fc "$DB_NAME" > "$work/kiva.dump" || die "pg_dump of $DB_NAME"
run_pg pg_restore --list < "$work/kiva.dump" > /dev/null || die "the dump of $DB_NAME can't be read back"

if [ -d "$MEDIA_ROOT" ]; then
    log "tar $MEDIA_ROOT"
    # Exit 1 is GNU tar's "a file changed while read": an upload landing meanwhile, harmless for files never rewritten.
    set +e
    tar --warning=no-file-changed -czf "$work/media.tgz" -C "$(dirname "$MEDIA_ROOT")" "$(basename "$MEDIA_ROOT")"
    status=$?
    set -e
    [ "$status" -le 1 ] || die "tar of $MEDIA_ROOT (exit $status)"
else
    log "no media folder at $MEDIA_ROOT; the backup has none"
    tar -czf "$work/media.tgz" --files-from /dev/null
fi

(cd "$work" && sha256sum kiva.dump media.tgz > SHA256SUMS)
rm -rf "$dir"
mv "$work" "$dir"

# Days are folder names (yyyy-mm-dd), so the oldest kept day compares as text.
oldest_kept="$(TZ=Asia/Tehran date -d "-$((KEEP_DAYS - 1)) days" +%F)"
for old in "$BACKUP_ROOT"/????-??-??; do
    [ -d "$old" ] || continue
    if [[ "$(basename "$old")" < "$oldest_kept" ]]; then
        rm -rf "$old"
        log "removed $(basename "$old"), older than $KEEP_DAYS days"
    fi
done
rm -rf "$BACKUP_ROOT"/*.partial

if [ -n "$RCLONE_REMOTE" ]; then
    log "copying to $RCLONE_REMOTE/$day"
    rclone copy "$dir" "$RCLONE_REMOTE/$day" || die "rclone copy to $RCLONE_REMOTE (the local backup is fine)"
fi

log "$day done in $(( $(date +%s) - started )) s: kiva.dump $(human "$dir/kiva.dump"), media.tgz $(human "$dir/media.tgz")"
