#!/usr/bin/env bash
# The server's half of tools/ops/deploy-fe.ps1 (LAUNCH.md, steps 6.1 and 6.4). Run as root:
#   bash deploy-fe-remote.sh shop|panel /tmp/kiva-<app>-release.tgz
#
# Layout of each frontend (made once by LAUNCH.md, step 6.1):
#   <root>/app        the release pm2 runs        <root>/app.prev   the one before it
#   <root>/.pm2       its own PM2_HOME            <root>/ecosystem.config.cjs   written here on every deploy
# The new release is unpacked beside the running one, so the switch is a rename and a restart. If it doesn't answer on
# 127.0.0.1:<port> within FE_HEALTH_TIMEOUT seconds, the previous release is put back and restarted, and the script
# fails. The broken one stays in <root>/app.failed to look at.
set -euo pipefail

APP="${1:?usage: deploy-fe-remote.sh shop|panel <tarball>}"
TARBALL="${2:?usage: deploy-fe-remote.sh shop|panel <tarball>}"

case "$APP" in
    shop)  ROOT="${KIVA_FE_ROOT:-/opt/kiva-fe}"; RUN_AS="${KIVA_FE_USER:-kivafe}"; PORT="${KIVA_FE_PORT:-3100}"; NAME="${KIVA_FE_PM2_NAME:-kiva-fe}" ;;
    panel) ROOT="${KIVA_FE_ROOT:-/opt/kiva-bo}"; RUN_AS="${KIVA_FE_USER:-kivabo}"; PORT="${KIVA_FE_PORT:-3200}"; NAME="${KIVA_FE_PM2_NAME:-kiva-bo}" ;;
    *) echo "deploy-fe: unknown app '$APP' (shop or panel)" >&2; exit 2 ;;
esac
HEALTH_TIMEOUT="${FE_HEALTH_TIMEOUT:-60}"

log() { echo "deploy-fe[$APP]: $*"; }
die() { echo "deploy-fe[$APP]: $*" >&2; exit 1; }

[ -f "$TARBALL" ] || die "no release at $TARBALL"
id "$RUN_AS" >/dev/null 2>&1 || die "user $RUN_AS doesn't exist; LAUNCH.md step 6.1 makes it"
[ -d "$ROOT" ] || die "$ROOT doesn't exist; LAUNCH.md step 6.1 makes it"
command -v pm2 >/dev/null || die "pm2 isn't installed (npm install -g pm2)"

as_app() { runuser -u "$RUN_AS" -- env PM2_HOME="$ROOT/.pm2" HOME="$ROOT" "$@"; }

# pm2's definition of the release in <root>/app, from the mode deploy-fe.ps1 wrote into it.
write_ecosystem() {
    local mode script args
    mode="$(sed -n 's/^mode=//p' "$ROOT/app/kiva-release.txt" 2>/dev/null || true)"
    if [ "$mode" = "standalone" ]; then
        script="server.js"; args=""
    else
        script="node_modules/next/dist/bin/next"; args="start -p $PORT -H 127.0.0.1"
    fi
    cat > "$ROOT/ecosystem.config.cjs" <<EOF
// Written by tools/ops/deploy-fe-remote.sh on every deploy; changes by hand don't last.
module.exports = {
  apps: [{
    name: "$NAME",
    cwd: "$ROOT/app",
    script: "$script",
    args: "$args",
    env: { NODE_ENV: "production", PORT: "$PORT", HOSTNAME: "127.0.0.1" },
    max_memory_restart: "600M",
  }],
};
EOF
    chown "$RUN_AS:$RUN_AS" "$ROOT/ecosystem.config.cjs"
}

restart() {
    write_ecosystem
    as_app pm2 startOrRestart "$ROOT/ecosystem.config.cjs" --update-env >/dev/null
    as_app pm2 save >/dev/null
}

answers() {
    local waited=0
    while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
        # Any answer below 400 is a page (the panel redirects to its login).
        if curl -fsS -o /dev/null --max-time 5 "http://127.0.0.1:$PORT/"; then return 0; fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

log "unpacking $(basename "$TARBALL")"
rm -rf "$ROOT/app.new"
mkdir -p "$ROOT/app.new"
tar -xzf "$TARBALL" -C "$ROOT/app.new"
[ -f "$ROOT/app.new/kiva-release.txt" ] || die "the release has no kiva-release.txt; was it made by deploy-fe.ps1?"
log "release: $(tr '\n' ' ' < "$ROOT/app.new/kiva-release.txt")"
chown -R "$RUN_AS:$RUN_AS" "$ROOT/app.new"
if [ "$(sed -n 's/^mode=//p' "$ROOT/app.new/kiva-release.txt")" = "npm" ]; then
    log "npm ci --omit=dev"
    (cd "$ROOT/app.new" && as_app npm ci --omit=dev --no-audit --no-fund)
fi

log "switching releases"
rm -rf "$ROOT/app.prev" "$ROOT/app.failed"
[ -d "$ROOT/app" ] && mv "$ROOT/app" "$ROOT/app.prev"
mv "$ROOT/app.new" "$ROOT/app"
restart

if answers; then
    rm -f "$TARBALL"
    log "live on 127.0.0.1:$PORT"
    exit 0
fi

log "no answer on 127.0.0.1:$PORT in ${HEALTH_TIMEOUT}s; putting the previous release back"
as_app pm2 logs "$NAME" --lines 30 --nostream || true
[ -d "$ROOT/app.prev" ] || die "there is no previous release to go back to; $APP is down"
mv "$ROOT/app" "$ROOT/app.failed"
mv "$ROOT/app.prev" "$ROOT/app"
restart
if answers; then
    die "the new release didn't start (kept in $ROOT/app.failed); the previous one is live again"
fi
die "neither the new release nor the previous one answers; $APP is down"
