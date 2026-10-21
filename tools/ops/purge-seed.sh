#!/usr/bin/env bash
# Runs purge_seed.sql on the live database (LAUNCH.md, step 6.7). As root, with the app stopped:
#   purge-seed.sh            dry run: everything is done and then rolled back; the NOTICE lines say what would go
#   purge-seed.sh --apply    a dump into /root/backups/before-purge-<time>.dump first, then the same, committed
# The app must be stopped (systemctl stop kiva): its jobs write to the same tables, and the triggers it lowers would
# otherwise wait for the app's connections.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SQL="${KIVA_PURGE_SQL:-$HERE/purge_seed.sql}"
DB_NAME="${KIVA_DB_NAME:-kiva}"
SERVICE="${KIVA_SERVICE:-kiva}"
BACKUP_ROOT="${KIVA_BACKUP_ROOT:-/root/backups}"

die() { echo "purge-seed: $*" >&2; exit 1; }

run_pg() {
    if [ -n "${PGHOST:-}" ] || [ "$(id -un)" = "postgres" ]; then "$@"; else runuser -u postgres -- "$@"; fi
}

apply="no"
case "${1:-}" in
    "") ;;
    --apply) apply="yes" ;;
    *) die "usage: purge-seed.sh [--apply]" ;;
esac

[ -f "$SQL" ] || die "no $SQL"
if command -v systemctl >/dev/null && systemctl is-active --quiet "$SERVICE"; then
    die "$SERVICE is running; stop it first: systemctl stop $SERVICE"
fi

if [ "$apply" = "yes" ]; then
    mkdir -p "$BACKUP_ROOT"
    dump="$BACKUP_ROOT/before-purge-$(date +%F-%H%M%S).dump"
    (umask 077 && run_pg pg_dump -Fc "$DB_NAME" > "$dump") || die "pg_dump failed; nothing was removed"
    echo "purge-seed: dump before the purge: $dump"
    ending="COMMIT;"
else
    echo "purge-seed: dry run; nothing is kept (run with --apply to keep it)"
    ending="ROLLBACK;"
fi

{ echo "BEGIN;"; cat "$SQL"; echo "$ending"; } | run_pg psql -X -q -v ON_ERROR_STOP=1 -d "$DB_NAME"

if [ "$apply" = "yes" ]; then
    echo "purge-seed: done. Keep KIVA_SEED_ENABLED=false in kiva.env, then: systemctl start $SERVICE"
fi
