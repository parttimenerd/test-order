#!/usr/bin/env bash
# =============================================================================
# reset-demo.sh — Reset both projects for a fresh demo run
# =============================================================================
# Use between practice runs or if something goes wrong on stage.
# If .baked-history/ exists (created by bake-history.sh), restores .test-order
# from snapshot instantly. Otherwise resets git state and pom.xml only.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
CAP_DIR="$SCRIPT_DIR/cap-sflight"
MODULE="cloudplatform/connectivity-destination-service"
BAKE_DIR="$SCRIPT_DIR/.baked-history"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Resetting demo state..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Reset cloud-sdk-java
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

# Commit the "off" state so make-change creates a clean diff
git add -A && git commit -m "Reset for demo" --allow-empty -q 2>/dev/null || true
echo "  ✓ Reset (plugin off, index present, no code changes)"

# Reset cap-sflight
echo ""
echo "▶ cap-sflight..."
cd "$CAP_DIR"
git checkout -- .
cd "$SCRIPT_DIR"
./toggle-test-order-cap.sh on 2>/dev/null || true

# Restore cap-sflight .test-order from baked snapshot (fast path)
if [[ -d "$BAKE_DIR/cap-sflight" ]]; then
    rm -rf "$CAP_DIR/srv/.test-order"
    mkdir -p "$CAP_DIR/srv/.test-order"
    cp -r "$BAKE_DIR/cap-sflight/." "$CAP_DIR/srv/.test-order/"
    echo "  ✓ cap-sflight .test-order restored from baked snapshot"
fi

# Re-plant the bug (git checkout restores the clean file)
HANDLER="$CAP_DIR/srv/src/main/java/com/sap/cap/sflight/processor/DeductDiscountHandler.java"
if grep -q "discount > 50" "$HANDLER" 2>/dev/null; then
    sed -i '' 's/discount > 50/discount >= 50/g' "$HANDLER"
    echo "  ✓ Reset (plugin on, bug planted: >= 50)"
else
    echo "  ✓ Reset (plugin on)"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready for demo!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " cloud-sdk-java: plugin OFF (show pain first)"
echo " cap-sflight:    plugin ON  (ready for agentic)"
if [[ -d "$BAKE_DIR" ]]; then
echo " history:        restored from .baked-history/"
else
echo " history:        ⚠  no baked history — run bake-history.sh"
fi
echo ""
