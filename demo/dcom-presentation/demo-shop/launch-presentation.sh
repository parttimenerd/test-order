#!/usr/bin/env bash
# =============================================================================
# launch-presentation.sh — One-shot launcher for the on-stage demo.
# =============================================================================
# Starts BOTH dashboards in the background and opens BOTH in the same browser:
#
#   • Tab 1 (port 8765) — cloud-sdk-java dashboard   (real production app)
#   • Tab 2 (port 8766) — demo-shop dashboard        (live edit target)
#
# Logs go to /tmp/test-order-dashboard-{sdk,shop}.log.
# Process IDs are written to /tmp/test-order-dashboard-{sdk,shop}.pid so the
# trap (and ./stop-presentation.sh) can clean them up.
#
# Re-running this script is safe: any previous PIDs are killed first.
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
PRES_DIR="$(cd "$DIR/.." && pwd)"
SDK_MODULE="$PRES_DIR/cloud-sdk-java/cloudplatform/connectivity-destination-service"
SHOP_DIR="$DIR"

SDK_PORT=8765
SHOP_PORT=8766

SDK_LOG=/tmp/test-order-dashboard-sdk.log
SHOP_LOG=/tmp/test-order-dashboard-shop.log
SDK_PID=/tmp/test-order-dashboard-sdk.pid
SHOP_PID=/tmp/test-order-dashboard-shop.pid

export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

MVN="$(command -v mvnd 2>/dev/null || echo mvn)"

# ── kill any previous run ────────────────────────────────────────────────────
kill_old() {
    local pidfile="$1"
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        kill "$(cat "$pidfile")" 2>/dev/null || true
    fi
    rm -f "$pidfile"
}
kill_old "$SDK_PID"
kill_old "$SHOP_PID"

# ── pre-flight: indices must exist ───────────────────────────────────────────
if [[ ! -f "$SHOP_DIR/.test-order/test-dependencies.lz4" ]]; then
    echo "✗ demo-shop has no learn index. Run ./prepare-shop.sh first." >&2
    exit 1
fi
if [[ ! -f "$SDK_MODULE/.test-order/test-dependencies.lz4" ]]; then
    echo "✗ cloud-sdk-java has no learn index. Run ../prepare.sh first." >&2
    exit 1
fi

# ── 1. start dashboards in background ────────────────────────────────────────
echo "▶ Starting cloud-sdk-java dashboard on :$SDK_PORT..."
(
    cd "$SDK_MODULE"
    exec $MVN -q test-order:serve \
        -Dtestorder.dashboard.port="$SDK_PORT" \
        -Dtestorder.dashboard.open=false \
        > "$SDK_LOG" 2>&1
) &
echo $! > "$SDK_PID"

echo "▶ Starting demo-shop dashboard on :$SHOP_PORT..."
(
    cd "$SHOP_DIR"
    exec $MVN -q test-order:serve \
        -Dtestorder.dashboard.port="$SHOP_PORT" \
        -Dtestorder.dashboard.open=false \
        > "$SHOP_LOG" 2>&1
) &
echo $! > "$SHOP_PID"

# ── 2. wait until both ports respond ─────────────────────────────────────────
wait_for_port() {
    local port="$1" name="$2" tries=60
    while (( tries-- > 0 )); do
        if curl -sf "http://localhost:$port/" >/dev/null 2>&1; then
            echo "  ✓ $name dashboard up at http://localhost:$port"
            return 0
        fi
        sleep 1
    done
    echo "  ✗ $name dashboard never came up — see log" >&2
    return 1
}
wait_for_port "$SDK_PORT"  "cloud-sdk-java" || { tail -20 "$SDK_LOG";  exit 1; }
wait_for_port "$SHOP_PORT" "demo-shop"      || { tail -20 "$SHOP_LOG"; exit 1; }

# ── 3. open BOTH in the same browser (default browser, two tabs) ─────────────
echo "▶ Opening both dashboards in the default browser..."
open "http://localhost:$SDK_PORT/"
sleep 0.5
open "http://localhost:$SHOP_PORT/"

# ── done ─────────────────────────────────────────────────────────────────────
cat <<EOF

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ✅ Presentation environment ready
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Tab 1 (real app)   http://localhost:$SDK_PORT/
  Tab 2 (demo-shop)  http://localhost:$SHOP_PORT/

  Logs:  $SDK_LOG
         $SHOP_LOG
  Stop:  ./stop-presentation.sh
EOF
