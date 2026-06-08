#!/usr/bin/env bash
# =============================================================================
# stop-presentation.sh — Kill the background dashboards started by
# launch-presentation.sh.
# =============================================================================
set -euo pipefail

for pidfile in /tmp/test-order-dashboard-sdk.pid /tmp/test-order-dashboard-shop.pid; do
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        kill "$(cat "$pidfile")" 2>/dev/null || true
        echo "  ✓ stopped $(basename "$pidfile" .pid)"
    fi
    rm -f "$pidfile"
done
