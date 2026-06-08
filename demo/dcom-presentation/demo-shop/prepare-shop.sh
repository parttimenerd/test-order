#!/usr/bin/env bash
# =============================================================================
# prepare-shop.sh — Bake the learn index for demo-shop.
# =============================================================================
# Runs `mvn test-order:learn test` once so the dashboard has data and the
# selective run on stage is fast. Idempotent: safe to re-run.
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

MVN="$(command -v mvnd 2>/dev/null || echo mvn)"

cd "$DIR"

echo "▶ Running learn pass (pass 1/2 — builds class map and instruments classes)..."
$MVN -q test-order:learn test

echo "▶ Running learn pass (pass 2/2 — bakes dependency index from pass 1 data)..."
$MVN -q test-order:learn test

echo ""
echo "  ✓ Learn index baked in $DIR/.test-order/"
echo ""

# Warm up the mvnd daemon so the first on-stage run is instant
if command -v mvnd &>/dev/null; then
    echo "▶ Warming up mvnd daemon..."
    mvnd -q test
    mvnd -q test-order:affected test -Dtestorder.changeMode=since-last-run -Dtestorder.affected.topN=2
    echo "  ✓ mvnd daemon warm — on-stage runs will be ~2x faster"
fi

echo ""
echo "  Next: ./launch-presentation.sh   (start dashboards)"
