#!/usr/bin/env bash
# =============================================================================
# d-kom Live Demo: End-to-End Setup
# =============================================================================
# Run this ONCE before the presentation to:
#   1. Build test-order from source
#   2. Initialize both demo projects (git, learn runs)
#   3. Verify everything works
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "╔══════════════════════════════════════════════════╗"
echo "║  d-kom Demo Setup                                ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Pre-flight checks ────────────────────────────────────────────────
if ! command -v mvn &>/dev/null; then
    echo "✗ Maven not found. Install with: brew install maven"
    exit 1
fi
if ! command -v java &>/dev/null; then
    echo "✗ Java not found. Install Java 17+."
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo "✗ Java 17+ required (found Java $JAVA_VER)"
    exit 1
fi

# ── Step 1: Build test-order ──────────────────────────────────────────
echo "━━━ Step 1: Building test-order from source ━━━"
cd "$ROOT_DIR"
mvn install -DskipTests -Dspotless.check.skip=true -q
echo "✓ test-order built and installed to local Maven repo"
echo ""

# ── Step 2: Legacy demo (olingo-style) ───────────────────────────────
echo "━━━ Step 2: Setting up Legacy demo ━━━"
cd "$SCRIPT_DIR/legacy-demo"

# Verify .buggy/ exists
if [ ! -f .buggy/UriParserImpl.java ]; then
    echo "✗ Missing .buggy/UriParserImpl.java — cannot set up legacy demo"
    exit 1
fi

# Ensure git is initialized
if [ ! -d .git ]; then
    git init -q
    git add -A
    git commit -m "Initial" -q
fi

# Ensure .gitignore always has .buggy/ entry
if ! grep -qx '.buggy/' .gitignore 2>/dev/null; then
    echo '.buggy/' >> .gitignore
    git add .gitignore
    git commit -m "Add .gitignore" -q --allow-empty
fi

# Clean learn run
rm -rf .test-order target
echo "  Running learn pass (this takes ~25s)..."
if ! mvn test -Dtestorder.mode=learn -Dspotless.check.skip=true; then
    echo "✗ Legacy demo learn run failed"
    exit 1
fi

# Validate state files were created
if [ ! -f .test-order/test-dependencies.lz4 ]; then
    echo "✗ Learn run did not create .test-order/test-dependencies.lz4"
    exit 1
fi

git add -A
git commit -m "Learn baseline" -q --allow-empty
echo "✓ Legacy demo ready (7 test classes, ~21s total)"
echo ""

# ── Step 3: Vibe demo (sflight-style) ────────────────────────────────
echo "━━━ Step 3: Setting up Vibe Coding demo ━━━"
cd "$SCRIPT_DIR/vibe-demo"

# Verify .buggy/ exists
if [ ! -f .buggy/BookingService.java ]; then
    echo "✗ Missing .buggy/BookingService.java — cannot set up vibe demo"
    exit 1
fi

# Ensure git is initialized
if [ ! -d .git ]; then
    git init -q
    git add -A
    git commit -m "Initial" -q
fi

# Ensure .gitignore always has .buggy/ entry
if ! grep -qx '.buggy/' .gitignore 2>/dev/null; then
    echo '.buggy/' >> .gitignore
    git add .gitignore
    git commit -m "Add .gitignore" -q --allow-empty
fi

# Clean learn run
rm -rf .test-order target
echo "  Running learn pass (this takes ~25s)..."
if ! mvn test -Dtestorder.mode=learn -Dspotless.check.skip=true; then
    echo "✗ Vibe demo learn run failed"
    exit 1
fi

# Validate state files were created
if [ ! -f .test-order/test-dependencies.lz4 ]; then
    echo "✗ Learn run did not create .test-order/test-dependencies.lz4"
    exit 1
fi

git add -A
git commit -m "Learn baseline" -q --allow-empty
echo "✓ Vibe demo ready (7 test classes, ~25s total)"
echo ""

# ── Step 4: Verification ─────────────────────────────────────────────
echo "━━━ Step 4: Verification ━━━"

echo ""
echo "▸ Legacy: introducing bug and testing..."
cd "$SCRIPT_DIR/legacy-demo"
cp .buggy/UriParserImpl.java src/main/java/org/apache/olingo/odata2/core/uri/UriParserImpl.java

LEGACY_OUT=$(mvn test -Dspotless.check.skip=true 2>&1 || true)
LEGACY_SAVED=$(echo "$LEGACY_OUT" | grep "Estimated time saved" || echo "NOT FOUND")
echo "  $LEGACY_SAVED"
git checkout -- src/

echo ""
echo "▸ Vibe: introducing AI bug and testing..."
cd "$SCRIPT_DIR/vibe-demo"
cp .buggy/BookingService.java src/main/java/com/sap/cap/sflight/booking/BookingService.java

VIBE_OUT=$(mvn test -Dspotless.check.skip=true 2>&1 || true)
VIBE_SAVED=$(echo "$VIBE_OUT" | grep "Estimated time saved" || echo "NOT FOUND")
echo "  $VIBE_SAVED"
git checkout -- src/

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  ✓ All demos ready!                              ║"
echo "║                                                  ║"
echo "║  Run:  ./run-legacy-demo.sh                      ║"
echo "║        ./run-vibe-demo.sh                        ║"
echo "║                                                  ║"
echo "║  Fallback: ./run-legacy-demo.sh --replay         ║"
echo "║            ./run-vibe-demo.sh --replay           ║"
echo "║                                                  ║"
echo "║  Slides: cd slides && npx slidev slides.md       ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Step 5: Pre-save replay files (fallback for live demo) ───────────
echo "━━━ Step 5: Saving replay files (live fallback) ━━━"
cd "$SCRIPT_DIR"
printf '\n\n' | bash run-legacy-demo.sh --save >/dev/null 2>&1 && echo "✓ Legacy replay saved" || echo "⚠ Legacy replay save failed (non-critical)"
printf '\n\n' | bash run-vibe-demo.sh --save >/dev/null 2>&1 && echo "✓ Vibe replay saved" || echo "⚠ Vibe replay save failed (non-critical)"
echo ""
echo "Done! You can now present with confidence."
echo "If live demo hangs: use ./run-*-demo.sh --replay"
