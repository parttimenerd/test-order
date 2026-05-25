#!/usr/bin/env bash
# =============================================================================
# bake-history.sh — Snapshot the current .test-order folders for fast resets
# =============================================================================
# Run ONCE after prepare.sh has built the full history.
# Saves cloud-sdk-java and cap-sflight .test-order folders to
# demo/dcom-presentation/.baked-history/ so reset-demo.sh can restore them
# instantly instead of re-running Maven.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
CAP_DIR="$SCRIPT_DIR/cap-sflight"
MODULE="cloudplatform/connectivity-destination-service"
BAKE_DIR="$SCRIPT_DIR/.baked-history"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Baking .test-order history snapshots"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Validate sources
SDK_INDEX="$SDK_DIR/$MODULE/.test-order/test-dependencies.lz4"
CAP_INDEX="$CAP_DIR/srv/.test-order/test-dependencies.lz4"

if [[ ! -f "$SDK_INDEX" ]]; then
    echo "  ✗ ERROR: $SDK_INDEX not found."
    echo "    Run prepare.sh first to build the index and history."
    exit 1
fi
if [[ ! -f "$CAP_INDEX" ]]; then
    echo "  ✗ ERROR: $CAP_INDEX not found."
    echo "    Run prepare.sh first to build the index and history."
    exit 1
fi

# Check state.lz4 exists (history)
SDK_STATE="$SDK_DIR/$MODULE/.test-order/state.lz4"
if [[ ! -f "$SDK_STATE" ]]; then
    echo "  ⚠ WARNING: No state.lz4 in cloud-sdk-java — history will be empty in dashboard."
fi

# Create bake dir
rm -rf "$BAKE_DIR"
mkdir -p "$BAKE_DIR/cloud-sdk-java" "$BAKE_DIR/cap-sflight"

echo ""
echo "▶ Snapshotting cloud-sdk-java .test-order..."
cp -r "$SDK_DIR/$MODULE/.test-order/." "$BAKE_DIR/cloud-sdk-java/"
# Remove any lock files — they're process-local
rm -f "$BAKE_DIR/cloud-sdk-java/"*.lock
echo "  ✓ $(du -sh "$BAKE_DIR/cloud-sdk-java" | cut -f1) saved"

echo ""
echo "▶ Snapshotting cap-sflight srv/.test-order..."
cp -r "$CAP_DIR/srv/.test-order/." "$BAKE_DIR/cap-sflight/"
rm -f "$BAKE_DIR/cap-sflight/"*.lock
echo "  ✓ $(du -sh "$BAKE_DIR/cap-sflight" | cut -f1) saved"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ History baked to .baked-history/"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " reset-demo.sh will now restore from this snapshot"
echo " instead of re-running Maven history cycles."
echo ""
