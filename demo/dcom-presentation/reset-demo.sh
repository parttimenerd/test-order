#!/usr/bin/env bash
# =============================================================================
# reset-demo.sh — Reset cloud-sdk-java for a fresh demo run
# =============================================================================
# Use between practice runs or if something goes wrong on stage.
# Restores .test-order from .baked-history/ snapshot (instant).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
MODULE="cloudplatform/connectivity-destination-service"
BAKE_DIR="$SCRIPT_DIR/.baked-history"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Resetting demo state..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
echo "▶ cloud-sdk-java..."
cd "$SDK_DIR"
git checkout -- .
cd "$SCRIPT_DIR"
# Plugin is committed as ON — turn it off for the pain demo
./toggle-test-order.sh off 2>/dev/null || true
cd "$SDK_DIR"

# Restore .test-order from baked snapshot (fast path)
if [[ -d "$BAKE_DIR/cloud-sdk-java" ]]; then
    rm -rf "$SDK_DIR/$MODULE/.test-order"
    mkdir -p "$SDK_DIR/$MODULE/.test-order"
    cp -r "$BAKE_DIR/cloud-sdk-java/." "$SDK_DIR/$MODULE/.test-order/"
    echo "  ✓ .test-order restored from baked snapshot"
else
    echo "  ⚠ No baked history found — run bake-history.sh after prepare.sh"
fi

# Commit the "off" state so make-change.sh creates a clean diff
git add -A && git commit -m "Reset for demo" --allow-empty -q 2>/dev/null || true
echo "  ✓ Reset (plugin off, index present, source clean)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready for demo!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " cloud-sdk-java: plugin OFF — show pain first"
if [[ -d "$BAKE_DIR" ]]; then
echo " history:        restored from .baked-history/"
else
echo " history:        ⚠  no baked history — run bake-history.sh"
fi
echo ""
