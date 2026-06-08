#!/usr/bin/env bash
# =============================================================================
# reset-shop.sh — Reset demo-shop to clean state between practice runs.
# =============================================================================
# Restores Cart.java from a baseline copy stored under .baseline/ and removes
# any test-order artefacts so the next run starts cold (or warm if you also
# run prepare-shop.sh afterwards).
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
BASELINE_DIR="$DIR/.baseline"
SRC_DIR="$DIR/src/main/java/com/example/shop"

# ── Capture baseline on first run ────────────────────────────────────────────
if [[ ! -d "$BASELINE_DIR" ]]; then
    mkdir -p "$BASELINE_DIR"
    cp "$SRC_DIR"/*.java "$BASELINE_DIR/"
    echo "  ✓ Baseline captured under .baseline/ (first-run snapshot)"
fi

# ── Restore production sources ───────────────────────────────────────────────
for f in "$BASELINE_DIR"/*.java; do
    cp "$f" "$SRC_DIR/$(basename "$f")"
done
echo "  ✓ Production sources restored from .baseline/"

# ── Wipe build + learn artefacts ─────────────────────────────────────────────
rm -rf "$DIR/.test-order" "$DIR/target"
echo "  ✓ .test-order/ and target/ removed"

echo ""
echo "  Next: ./prepare-shop.sh   (run a learn pass before going on stage)"
