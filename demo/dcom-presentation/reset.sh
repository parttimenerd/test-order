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
POM="$SDK_DIR/$MODULE/pom.xml"
BAKE_DIR="$SCRIPT_DIR/.baked-history/cloud-sdk-java"
RESOLVER="$SDK_DIR/$MODULE/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Resetting demo state..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── 1. Remove plugin from pom.xml ───────────────────────────────────────────

echo ""
echo "▶ Removing test-order plugin from pom.xml..."
if grep -q "test-order-plugin-start" "$POM" 2>/dev/null; then
    perl -i -ne 'print unless /test-order-plugin-start/ .. /test-order-plugin-end/' "$POM"
    echo "  ✓ Plugin removed"
else
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

# ── 3. Restore .test-order from baked snapshot ──────────────────────────────

echo ""
echo "▶ Restoring learn index..."
if [[ -d "$BAKE_DIR" ]]; then
    rm -rf "$SDK_DIR/$MODULE/.test-order"
    mkdir -p "$SDK_DIR/$MODULE/.test-order"
    cp -r "$BAKE_DIR/." "$SDK_DIR/$MODULE/.test-order/"
    echo "  ✓ .test-order restored from .baked-history/"
else
    echo "  ⚠  No baked index found — run prepare.sh first"
fi

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
echo "  index:   ready in .test-order/"
echo ""
