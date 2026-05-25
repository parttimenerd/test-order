#!/usr/bin/env bash
# =============================================================================
# bake-history.sh — Snapshot the current .test-order folder for fast resets
# =============================================================================
# Run ONCE after prepare.sh has built the full history.
# Saves cloud-sdk-java .test-order to .baked-history/ so reset-demo.sh can
# restore it instantly instead of re-running Maven.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
MODULE="cloudplatform/connectivity-destination-service"
BAKE_DIR="$SCRIPT_DIR/.baked-history"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Baking .test-order history snapshot"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

SDK_INDEX="$SDK_DIR/$MODULE/.test-order/test-dependencies.lz4"
if [[ ! -f "$SDK_INDEX" ]]; then
    echo "  ✗ ERROR: $SDK_INDEX not found."
    echo "    Run prepare.sh first to build the index and history."
    exit 1
fi

rm -rf "$BAKE_DIR"
mkdir -p "$BAKE_DIR/cloud-sdk-java"

echo ""
echo "▶ Snapshotting cloud-sdk-java .test-order..."
cp -r "$SDK_DIR/$MODULE/.test-order/." "$BAKE_DIR/cloud-sdk-java/"
rm -f "$BAKE_DIR/cloud-sdk-java/"*.lock
echo "  ✓ $(du -sh "$BAKE_DIR/cloud-sdk-java" | cut -f1) saved"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ History baked to .baked-history/"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " reset-demo.sh will restore from this snapshot instantly."
echo ""
