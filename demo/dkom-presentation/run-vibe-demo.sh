#!/usr/bin/env bash
# =============================================================================
# d-kom Demo: Vibe Coding (SAP CAP sflight-style) — Interactive Script
# =============================================================================
# Shows Demo 2: AI-generated code with a subtle bug.
# Run after setup-all.sh has been executed.
#
# Usage:
#   ./run-vibe-demo.sh           # Live execution (streaming output)
#   ./run-vibe-demo.sh --replay  # Replay from saved output (fallback)
#   ./run-vibe-demo.sh --save    # Live execution + save output for replay
# =============================================================================
set -euo pipefail

# ── ANSI colors ───────────────────────────────────────────────────────
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
CYAN='\033[1;36m'
DIM='\033[2m'
RESET='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPLAY_FILE="$SCRIPT_DIR/.replay/vibe-demo.txt"
REPLAY_MODE=false
SAVE_MODE=false

if [[ "${1:-}" == "--replay" ]]; then
    REPLAY_MODE=true
    if [ ! -f "$REPLAY_FILE" ]; then
        echo "  ✗ No replay file found. Run with --save first."
        exit 1
    fi
elif [[ "${1:-}" == "--save" ]]; then
    SAVE_MODE=true
    mkdir -p "$SCRIPT_DIR/.replay"
fi

cd "$SCRIPT_DIR/vibe-demo"

# ── Safety: always revert source on exit/interrupt ────────────────────
trap 'git checkout -- src/ 2>/dev/null; exit' EXIT INT TERM

# ── Pre-flight checks ────────────────────────────────────────────────
if [ "$REPLAY_MODE" = false ]; then
    if [ ! -f .buggy/BookingService.java ]; then
        echo "  ✗ Missing .buggy/BookingService.java — run setup-all.sh first"
        exit 1
    fi
    if [ ! -d .test-order ]; then
        echo "  ✗ No .test-order/ directory — run setup-all.sh first"
        exit 1
    fi
fi

# ── Helper: colorize test output ─────────────────────────────────────
colorize() {
    while IFS= read -r line; do
        if [[ "$line" == *"FAILURE"* ]] || [[ "$line" == *"Failures: "* && "$line" != *"Failures: 0"* ]]; then
            printf "  ${RED}%s${RESET}\n" "$line"
        elif [[ "$line" == *"[test-order]"* ]]; then
            printf "  ${CYAN}%s${RESET}\n" "$line"
        elif [[ "$line" == *"Failures: 0"* ]]; then
            printf "  ${DIM}%s${RESET}\n" "$line"
        else
            printf "  %s\n" "$line"
        fi
    done
}

# ── Introduce bug ────────────────────────────────────────────────────
if [ "$REPLAY_MODE" = false ]; then
    cp .buggy/BookingService.java src/main/java/com/sap/cap/sflight/booking/BookingService.java
fi

clear
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  SAP CAP sflight — AI adds VIP discount (with a bug)   ║"
echo "║  7 test classes · Bug in VipDiscountTest (last alpha)   ║"
echo "╠════════════════════════════════════════════════════════════╣"
echo "║  AI wrote:  price.subtract(BigDecimal.valueOf(20))      ║"
echo "║  Correct:   price.multiply(BigDecimal.valueOf(0.80))    ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── WITHOUT test-order ───────────────────────────────────────────────
read -p "  ▶ Press Enter to run WITHOUT test-order (alphabetical)..."
echo ""
printf "  ${DIM}┌─ mvn test -Dtestorder.mode=skip ──────────────────────${RESET}\n"

if [ "$REPLAY_MODE" = true ]; then
    sed -n '/^## RUN1 START$/,/^## RUN1 END$/{ /^##/d; p; }' "$REPLAY_FILE" | colorize
else
    START1=$SECONDS
    RUN1_OUT=$(timeout 120 mvn test -Dtestorder.mode=skip -Dsurefire.runOrder=alphabetical -Dspotless.check.skip=true 2>&1 \
        | grep --line-buffered -E "Tests run:.*Time elapsed" \
        | sed -u 's/\[INFO\] //; s/\[ERROR\] //' || true)
    ELAPSED1=$(( SECONDS - START1 ))
    echo "$RUN1_OUT" | colorize
fi

printf "  ${DIM}└──────────────────────────────────────────────────────${RESET}\n"
echo ""
if [ "$REPLAY_MODE" = false ]; then
    printf "  ${RED}╭──────────────────────────────────────────────╮${RESET}\n"
    printf "  ${RED}│  💥 Bug at position 7/7 — waited %ss        │${RESET}\n" "$ELAPSED1"
    printf "  ${RED}╰──────────────────────────────────────────────╯${RESET}\n"
else
    printf "  ${RED}╭──────────────────────────────────────────────╮${RESET}\n"
    printf "  ${RED}│  💥 Bug at position 7/7 — waited ~24s        │${RESET}\n"
    printf "  ${RED}╰──────────────────────────────────────────────╯${RESET}\n"
fi
echo ""

# ── WITH test-order ──────────────────────────────────────────────────
read -p "  ▶ Press Enter to run WITH test-order (prioritized)..."
echo ""
printf "  ${DIM}┌─ mvn test ────────────────────────────────────────────${RESET}\n"

if [ "$REPLAY_MODE" = true ]; then
    sed -n '/^## RUN2 START$/,/^## RUN2 END$/{ /^##/d; p; }' "$REPLAY_FILE" | colorize
else
    START2=$SECONDS
    RUN2_OUT=$(timeout 120 mvn test -Dspotless.check.skip=true 2>&1 \
        | grep --line-buffered -E "\[test-order\]|Tests run:.*Time elapsed" \
        | grep -v "lock file\|resourceDirectory" \
        | sed -u 's/\[INFO\] //; s/\[ERROR\] //; s/^WARNING: //' || true)
    ELAPSED2=$(( SECONDS - START2 ))
    echo "$RUN2_OUT" | colorize
fi

printf "  ${DIM}└──────────────────────────────────────────────────────${RESET}\n"
echo ""
if [ "$REPLAY_MODE" = false ]; then
    printf "  ${GREEN}╭──────────────────────────────────────────────╮${RESET}\n"
    printf "  ${GREEN}│  ✅ Bug at position 1/7 — found in <1s       │${RESET}\n"
    printf "  ${GREEN}│  ⏱️  Time saved: %ss                         │${RESET}\n" "$ELAPSED1"
    printf "  ${GREEN}╰──────────────────────────────────────────────╯${RESET}\n"
else
    printf "  ${GREEN}╭──────────────────────────────────────────────╮${RESET}\n"
    printf "  ${GREEN}│  ✅ Bug at position 1/7 — found in <1s       │${RESET}\n"
    printf "  ${GREEN}│  ⏱️  Time saved: ~24s                         │${RESET}\n"
    printf "  ${GREEN}╰──────────────────────────────────────────────╯${RESET}\n"
fi
echo ""

# ── Save replay data if requested ────────────────────────────────────
if [ "$SAVE_MODE" = true ]; then
    {
        echo "## RUN1 START"
        echo "$RUN1_OUT"
        echo "## RUN1 END"
        echo "## RUN2 START"
        echo "$RUN2_OUT"
        echo "## RUN2 END"
    } > "$REPLAY_FILE"
    echo "  ✓ Replay saved to $REPLAY_FILE"
fi

# ── Clean up (also handled by EXIT trap) ─────────────────────────────
git checkout -- src/
echo "  ✓ Code reverted"
echo ""
