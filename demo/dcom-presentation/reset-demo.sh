#!/usr/bin/env bash
# =============================================================================
# reset-demo.sh — Reset both projects for a fresh demo run
# =============================================================================
# Use between practice runs or if something goes wrong on stage.
# Does NOT rebuild or re-learn — just resets git state and pom.xml.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
CAP_DIR="$SCRIPT_DIR/cap-sflight"

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
echo "  ✓ Reset (plugin on, no code changes)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready for demo!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " cloud-sdk-java: plugin OFF (show pain first)"
echo " cap-sflight:    plugin ON  (ready for agentic)"
echo ""
