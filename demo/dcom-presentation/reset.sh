#!/usr/bin/env bash
# =============================================================================
# reset.sh — Reset demo to clean starting state
# =============================================================================
# Run directly before going on stage (or between practice runs).
# - Removes the test-order plugin from pom.xml
# - Restores source file to clean state (no bug)
# - Restores .test-order from .baked-history/ (instant, no Maven needed)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$SCRIPT_DIR/cloud-sdk-java"
MODULE="cloudplatform/connectivity-destination-service"
ROOT_POM="$SDK_DIR/pom.xml"
MODULE_POM="$SDK_DIR/$MODULE/pom.xml"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"
RESOLVER="$SDK_DIR/$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Resetting demo state..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── 1. Remove plugin from pom.xml (root + module fallback) ──────────────────

echo ""
echo "▶ Removing test-order plugin from pom.xml..."
removed=false
for pom in "$ROOT_POM" "$MODULE_POM"; do
    if grep -q "test-order-plugin-start" "$pom" 2>/dev/null; then
        perl -i -ne 'print unless /test-order-plugin-start/ .. /test-order-plugin-end/' "$pom"
        echo "  ✓ Plugin removed from $(basename "$(dirname "$pom")")/pom.xml"
        removed=true
    fi
done
if [[ "$removed" == "false" ]]; then
    echo "  ✓ Already clean"
fi

# ── 2. Restore source to clean state ────────────────────────────────────────

echo ""
echo "▶ Restoring source files..."
cd "$SDK_DIR"
# Fix any lingering negation bug (also handles committed versions)
sed -i '' 's/return !Objects.equals(currentTenantId, providerTenantId);/return Objects.equals(currentTenantId, providerTenantId);/' "$RESOLVER" 2>/dev/null || true
git checkout -- . 2>/dev/null || true
echo "  ✓ Source clean"

# ── 3. Remove .test-order from any prior run (add-test-order.sh restores it) ─

echo ""
echo "▶ Clearing learn index..."
rm -rf "$SDK_DIR/.test-order" "$SDK_DIR/$MODULE/.test-order"
echo "  ✓ .test-order/ cleared (will be restored by add-test-order.sh)"

# ── 4. Commit clean state so change-detection baseline is correct ───────────

echo ""
cd "$SDK_DIR"
git add -A && git commit -m "Reset for demo" --allow-empty -q 2>/dev/null || true
echo "  ✓ Git baseline committed"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " ✅ Ready for demo!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "  plugin:  OFF (pom.xml clean)"
echo "  source:  clean (no bug)"
echo "  index:   cleared — run ./add-test-order.sh to restore"
echo ""
