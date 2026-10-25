#!/usr/bin/env bash
# Proves a backup can be brought back (LAUNCH.md, step 6.3). As root:
#   restore-test.sh                     the latest day under /root/backups
#   restore-test.sh /root/backups/2026-10-21 [--keep]
# Checks SHA256SUMS, restores kiva.dump into a fresh database kiva_restore_test, compares its counts with the live
# database (printed side by side) and its migration with the live one, lists media.tgz against the media folder, then
# drops the test database (--keep leaves it to look at). The live database is only read. The last line is the one for
# the work log.
set -euo pipefail

BACKUP_ROOT="${KIVA_BACKUP_ROOT:-/root/backups}"
DB_NAME="${KIVA_DB_NAME:-kiva}"
TEST_DB="${KIVA_RESTORE_TEST_DB:-kiva_restore_test}"
MEDIA_ROOT="${KIVA_MEDIA_ROOT:-/var/lib/kiva/media}"

dir="${1:-}"
keep="no"
[ "${2:-}" = "--keep" ] && keep="yes"
[ "$dir" = "--keep" ] && { dir=""; keep="yes"; }

log() { echo "restore-test: $*"; }
die() { echo "restore-test: FAILED: $*" >&2; exit 1; }

run_pg() {
    if [ -n "${PGHOST:-}" ] || [ "$(id -un)" = "postgres" ]; then "$@"; else runuser -u postgres -- "$@"; fi
}
scalar() { run_pg psql -X -At -v ON_ERROR_STOP=1 -d "$1" -c "$2"; }

[[ "$TEST_DB" == *_test ]] || die "the test database must end with _test, not '$TEST_DB'"
[ "$TEST_DB" != "$DB_NAME" ] || die "the test database can't be the live one"

if [ -z "$dir" ]; then
    dir="$(ls -1d "$BACKUP_ROOT"/????-??-?? 2>/dev/null | tail -n 1 || true)"
    [ -n "$dir" ] || die "no backup under $BACKUP_ROOT"
fi
[ -f "$dir/kiva.dump" ] || die "no kiva.dump in $dir"

log "checking $dir/SHA256SUMS"
(cd "$dir" && sha256sum --quiet -c SHA256SUMS) || die "a file of $dir doesn't match its checksum"

log "restoring into $TEST_DB"
run_pg dropdb --if-exists "$TEST_DB"
run_pg createdb "$TEST_DB"
run_pg pg_restore --exit-on-error -d "$TEST_DB" < "$dir/kiva.dump" || die "pg_restore into $TEST_DB"

# What the backup had against what the live database has now. The live one usually has more since the night; less is
# only a warning, since the nightly jobs remove unused media and old rows, and the seed purge (step 6.7) removes a lot.
checks=(
    "orders|SELECT count(*) FROM orderqm_tbl"
    "paid orders|SELECT count(*) FROM orderqm_tbl WHERE \"paidOn\" IS NOT NULL"
    "payments|SELECT count(*) FROM payment_tbl"
    "succeeded payments|SELECT count(*) FROM payment_tbl WHERE status IN ('SUCCEEDED', 'REFUNDED')"
    "refunds|SELECT count(*) FROM refund_tbl"
    "products|SELECT count(*) FROM productqm_tbl"
    "users|SELECT count(*) FROM user_tbl"
    "events|SELECT count(*) FROM eventstore_tbl"
    "media rows|SELECT count(*) FROM media_tbl"
)
summary=""
printf '%-20s %12s %12s\n' "" "backup" "live now"
for check in "${checks[@]}"; do
    name="${check%%|*}"
    sql="${check#*|}"
    restored="$(scalar "$TEST_DB" "$sql")"
    live="$(scalar "$DB_NAME" "$sql")"
    printf '%-20s %12s %12s\n' "$name" "$restored" "$live"
    [ "$restored" -le "$live" ] || log "warning: $name: the backup has $restored, more than the live $live"
    case "$name" in orders|payments|refunds) summary="$summary $restored $name," ;; esac
done

migration_sql="SELECT max(version::int) FROM flyway_schema_history WHERE success"
restored_migration="$(scalar "$TEST_DB" "$migration_sql")"
live_migration="$(scalar "$DB_NAME" "$migration_sql")"
log "migration V$restored_migration in the backup, V$live_migration live"
[ "$restored_migration" -le "$live_migration" ] || die "the backup is ahead of the live database"

in_tar="$(tar -tzf "$dir/media.tgz" | grep -cv '/$' || true)"
on_disk=0
[ -d "$MEDIA_ROOT" ] && on_disk="$(find "$MEDIA_ROOT" -type f | wc -l)"
log "media: $in_tar files in media.tgz, $on_disk on disk now"
[ "$in_tar" -le "$on_disk" ] || log "warning: media.tgz has more files than the media folder (unused uploads are removed nightly)"

if [ "$keep" = "yes" ]; then
    log "$TEST_DB kept; drop it with: runuser -u postgres -- dropdb $TEST_DB"
else
    run_pg dropdb "$TEST_DB"
fi

log "OK $(basename "$dir"):${summary} migration V$restored_migration, $in_tar media files"
