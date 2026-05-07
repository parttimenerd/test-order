#!/usr/bin/env bash
#
# Real end-to-end test of the score optimisation workflow.
#
# Uses samples/sample-basic (6 test classes, 20 tests) and introduces
# controlled bugs between runs to build up a realistic failure history,
# then runs the optimizer and verifies the result.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SAMPLE="$ROOT/samples/sample-basic"
SRC="$SAMPLE/src/main/java/com/myapp"
LOG="/tmp/optimizer-phases.log"

# Colours (disabled if not a tty)
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}▸ $*${NC}"; echo "▸ $*" >> "$LOG"; }
ok()    { echo -e "${GREEN}✓ $*${NC}"; echo "✓ $*" >> "$LOG"; }
warn()  { echo -e "${YELLOW}⚠ $*${NC}"; echo "⚠ $*" >> "$LOG"; }
fail()  { echo -e "${RED}✗ $*${NC}"; echo "✗ $*" >> "$LOG"; }

> "$LOG"  # truncate log

# --- Setup ----------------------------------------------------------------

info "Installing test-order plugin from source..."
if (cd "$ROOT" && mvn install -DskipTests -Dspotless.check.skip=true -pl test-order-core,test-order-annotations,test-order-junit,test-order-maven-plugin -am -q 2>&1); then
    ok "Plugin installed"
else
    fail "Plugin install failed"; exit 1
fi

info "Clearing old state in sample-basic..."
rm -rf "$SAMPLE/.test-order" "$SAMPLE/target"
ok "State cleared"

# Helper: run tests in sample-basic with given mode, tolerate failures
run_tests() {
    local mode="$1"
    local label="$2"
    info "Run: $label (mode=$mode)"
    local output
    output=$(cd "$SAMPLE" && mvn clean test \
        -Dspotless.check.skip=true \
        -Dtestorder.mode="$mode" 2>&1) || true
    echo "$output" | grep -E '\[test-order\]|Tests run:|FAILURE|ERROR.*<<<|Run APFD|BUILD' || true
    echo "$output" | grep -E 'Tests run:|BUILD' >> "$LOG" || true
}

# Helper: backup a source file before mutating
backup() { cp -p "$1" "$1.bak"; }
# Helper: restore from backup and touch so Maven recompiles
restore() {
    if [[ -f "$1.bak" ]]; then
        mv "$1.bak" "$1" && touch "$1"
    else
        warn "No backup found for $1"
    fi
}

# Trap to restore files on exit
cleanup() {
    for f in "$SRC"/util/Validator.java "$SRC"/util/StringUtils.java \
             "$SRC"/model/Product.java "$SRC"/model/User.java \
             "$SRC"/service/OrderService.java; do
        [[ -f "$f.bak" ]] && mv "$f.bak" "$f" && touch "$f"
    done
}
trap cleanup EXIT

# --- Phase 1: Initial learn run (all pass) --------------------------------

info "Phase 1: Initial learn run"
run_tests learn "baseline (all pass)"
ok "Learn run complete"

# --- Phase 2: Bug in Validator → UserServiceTest fails ---------------------

info "Phase 2: Introducing bug in Validator.isValidEmail()"
backup "$SRC/util/Validator.java"
# Break email validation: always return false
sed -i '' 's/return email != null && email.contains("@") && email.contains(".");/return false;/' \
    "$SRC/util/Validator.java"

run_tests order "bug in Validator (UserServiceTest should fail)"
restore "$SRC/util/Validator.java"
ok "Validator bug introduced + reverted"

# --- Phase 3: All pass (bug fixed) ----------------------------------------

info "Phase 3: All-pass run (bug fixed)"
run_tests order "all pass after fix"
ok "All-pass run recorded"

# --- Phase 4: Bug in Product.applyDiscount → ProductTest + OrderServiceTest fail

info "Phase 4: Introducing bug in Product.applyDiscount()"
backup "$SRC/model/Product.java"
# Make applyDiscount return wrong value (use | delimiter to avoid escaping issues)
sed -i '' 's|return price \* (1 - percent / 100.0);|return price * percent;|' \
    "$SRC/model/Product.java"

run_tests order "bug in Product (ProductTest + OrderServiceTest should fail)"
restore "$SRC/model/Product.java"
ok "Product bug introduced + reverted"

# --- Phase 5: Bug in StringUtils.capitalize → StringUtilsTest fails --------

info "Phase 5: Introducing bug in StringUtils.capitalize()"
backup "$SRC/util/StringUtils.java"
sed -i '' 's/return s.substring(0, 1).toUpperCase() + s.substring(1);/return s.toLowerCase();/' \
    "$SRC/util/StringUtils.java"

run_tests order "bug in StringUtils (StringUtilsTest should fail)"
restore "$SRC/util/StringUtils.java"
ok "StringUtils bug introduced + reverted"

# --- Phase 6: Bug in User.isAdult → UserTest + UserServiceTest fail --------

info "Phase 6: Introducing bug in User.isAdult()"
backup "$SRC/model/User.java"
sed -i '' 's/return age >= 18;/return age >= 99;/' \
    "$SRC/model/User.java"

run_tests order "bug in User (UserTest + UserServiceTest should fail)"
restore "$SRC/model/User.java"
ok "User bug introduced + reverted"

# --- Phase 7: All pass again ----------------------------------------------

info "Phase 7: All-pass run"
run_tests order "all pass"
ok "All-pass run recorded"

# --- Phase 8: Bug in Validator again (same area, different pattern) --------

info "Phase 8: Introducing bug in Validator.isValidName()"
backup "$SRC/util/Validator.java"
sed -i '' 's/return name != null && !name.isBlank() && name.length() <= 100;/return false;/' \
    "$SRC/util/Validator.java"

run_tests order "bug in Validator.isValidName (UserServiceTest should fail)"
restore "$SRC/util/Validator.java"
ok "Validator name bug introduced + reverted"

# --- Phase 9: Bug in OrderService → OrderServiceTest fails ----------------

info "Phase 9: Introducing bug in OrderService.getTotal()"
backup "$SRC/service/OrderService.java"
sed -i '' 's/return cart.stream().mapToDouble(Product::getPrice).sum();/return 0.0;/' \
    "$SRC/service/OrderService.java"

run_tests order "bug in OrderService (OrderServiceTest should fail)"
restore "$SRC/service/OrderService.java"
ok "OrderService bug introduced + reverted"

# --- Phase 10: One more learn run to ensure fresh dependency data ----------

info "Phase 10: Final learn run"
run_tests learn "final learn"
ok "Final learn run complete"

# ═══════════════════════════════════════════════════════════════════════════
# Analyze results
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "═══════════════════════════════════════════════════════════════════"
info "Running optimizer..."
echo "═══════════════════════════════════════════════════════════════════"

OPTIMIZE_OUTPUT=$( (cd "$SAMPLE" && mvn test-order:optimize -Dspotless.check.skip=true 2>&1) || true )
echo "$OPTIMIZE_OUTPUT"
echo "$OPTIMIZE_OUTPUT" >> "$LOG"

echo ""
echo "═══════════════════════════════════════════════════════════════════"
info "Final state diagnostics:"
echo "═══════════════════════════════════════════════════════════════════"

DIAG_OUTPUT=$( (cd "$SAMPLE" && mvn test-order:diagnose -Dspotless.check.skip=true 2>&1) || true )
echo "$DIAG_OUTPUT"
echo "$DIAG_OUTPUT" >> "$LOG"

echo ""
echo "═══════════════════════════════════════════════════════════════════"
ok "Experiment complete!"
echo "═══════════════════════════════════════════════════════════════════"
info "Full log at: $LOG"
