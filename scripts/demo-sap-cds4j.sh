#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# test-order demo with SAP cds4j (real-world CAP Java SDK)
#
# Shows how test-order selects only the affected tests when a bug is introduced
# in ResultImpl.java — running 15 tests instead of 1,491 in cds4j-core alone.
#
# Usage:
#   bash scripts/demo-sap-cds4j.sh
#
# Requirements:
#   - test-order 0.0.1-SNAPSHOT installed to ~/.m2
#     (cd /path/to/test-order && mvn install -DskipTests -pl test-order-core,test-order-maven-plugin)
#   - SAP cds4j checkout at sap-tests/cds4j (sibling of test-order root)
#   - cds4j must have been learned already (.test-order/ index present)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CDS4J_DIR="$(cd "$ROOT_DIR/../sap-tests/cds4j" && pwd)"
PATCH_FILE="$SCRIPT_DIR/bugs/cds4j/resultimpl-single-wrong-exception.patch"

banner() {
  echo
  echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════${RESET}"
  echo -e "${CYAN}${BOLD}  $1${RESET}"
  echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════${RESET}"
  echo
}

step() { echo -e "${GREEN}▸ $1${RESET}"; }
info() { echo -e "  $1"; }
ok()   { echo -e "${GREEN}  ✔ $1${RESET}"; }
warn() { echo -e "${YELLOW}  ⚠ $1${RESET}"; }
err()  { echo -e "${RED}  ✘ $1${RESET}"; }

typecmd() {
  local cmd="$1"
  echo
  echo -e "${YELLOW}\$ ${cmd}${RESET}"
  sleep 0.2
  eval "$cmd"
}

pause() { sleep "${DEMO_FAST:+0}${DEMO_FAST:-${1:-1.5}}"; }

# ── Preflight ─────────────────────────────────────────────────────────────────
banner "test-order · SAP cds4j demo"
echo -e "Demonstrates ${BOLD}test-order${RESET} on ${BOLD}SAP cds4j${RESET} — the core Java library"
echo -e "for SAP Cloud Application Programming Model (CAP)."
echo
echo -e "  Project : cds4j-core  (${BOLD}1,491${RESET} @Test methods across 112 test files)"
echo -e "  Bug     : ResultImpl.single() throws wrong exception"
echo -e "  Goal    : Show test-order selects only the 15 affected tests"
echo
pause 2

if [[ ! -f "$PATCH_FILE" ]]; then
  err "Patch file missing: $PATCH_FILE"
  exit 1
fi

if [[ ! -d "$CDS4J_DIR/.test-order" ]]; then
  err "No test-order index found at $CDS4J_DIR/.test-order"
  err "Please run: cd $CDS4J_DIR && mvn test -pl cds4j-core -Dtestorder.learn=true"
  exit 1
fi

# Make sure we're on a clean state
cd "$CDS4J_DIR"
if ! git diff --quiet HEAD -- cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java 2>/dev/null; then
  warn "ResultImpl.java has uncommitted changes — reverting..."
  git checkout -- cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java
fi

# ── Show the learned index ────────────────────────────────────────────────────
banner "Step 1: The learned dependency index"
step "cds4j was already instrumented with 'mvn test' (learn phase)"
info "The .test-order/ directory stores which test classes depend on which production classes:"
echo
typecmd "ls -lh '$CDS4J_DIR/.test-order/'"
echo
info "test-dependencies.lz4 maps every test class → its bytecode-level production dependencies."
info "When a class changes, test-order knows exactly which tests to run."
pause 2

# ── Show total test count ─────────────────────────────────────────────────────
banner "Step 2: The scale — without test-order"
step "How many tests does a full run execute in cds4j-core?"
echo
info "  @Test methods in cds4j-core: $(find "$CDS4J_DIR/cds4j-core/src/test" -name "*Test.java" | xargs grep -c "@Test" 2>/dev/null | awk -F: '{sum+=$2} END{print sum}')"
info "  Test files:                  $(find "$CDS4J_DIR/cds4j-core/src/test" -name "*Test.java" | wc -l | tr -d ' ')"
echo
info "A full 'mvn test' run in cds4j-core takes several minutes."
info "With test-order, only tests affected by the changed class are run."
pause 2

# ── Show which tests depend on ResultImpl ─────────────────────────────────────
banner "Step 3: The changed class — ResultImpl.java"
step "We will introduce a bug in ResultImpl.java"
info "  File: cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java"
info "  Bug:  single() throws NonUniqueResultException on EMPTY result"
info "        instead of EmptyResultException"
echo
info "The bug is subtle — a simple swap of the exception supplier variable."
info "In production, callers relying on EmptyResultException.catch() would silently"
info "get the wrong error message and potentially wrong error handling."
pause 2

# ── Apply the patch ───────────────────────────────────────────────────────────
banner "Step 4: Inject the synthetic bug"
typecmd "patch -p1 < '$PATCH_FILE'"
echo
step "Bug injected — the changed class is:"
info "  com.sap.cds.impl.ResultImpl"
echo
step "Git diff confirms the change:"
typecmd "git diff cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java"
pause 1

# ── Run affected selection ────────────────────────────────────────────────────
banner "Step 5: test-order selects only affected tests"
step "Running: mvn test-order:affected -Dtestorder.changed.classes=com.sap.cds.impl.ResultImpl"
echo
info "test-order will look up which test classes have ResultImpl in their dependency set."
info "Only those tests will be run — all others are skipped."
echo

AFFECTED_LOG="$ROOT_DIR/target/demo-cds4j-affected.log"
mkdir -p "$ROOT_DIR/target"

typecmd "cd '$CDS4J_DIR' && mvn me.bechberger:test-order-maven-plugin:affected \
  -pl cds4j-core \
  -Dtestorder.mode=skip \
  -Dtestorder.changeMode=explicit \
  '-Dtestorder.changed.classes=com.sap.cds.impl.ResultImpl' \
  -Dtestorder.affected.topN=-1 \
  -B -q 2>&1 | tee '$AFFECTED_LOG' | tail -20 || true"

echo
if grep -q "Tests run:" "$AFFECTED_LOG" 2>/dev/null; then
  TESTS_RUN=$(grep "Tests run:" "$AFFECTED_LOG" | grep -v "0 tests" | tail -1 || true)
  ok "Test run summary: $TESTS_RUN"
fi
pause 1

# ── Show selected tests ───────────────────────────────────────────────────────
banner "Step 6: Which tests were selected?"
step "Tests selected by test-order for com.sap.cds.impl.ResultImpl:"
echo
if [[ -f "$CDS4J_DIR/cds4j-core/target/test-order-selected.txt" ]]; then
  typecmd "cat '$CDS4J_DIR/cds4j-core/target/test-order-selected.txt'"
else
  # Parse from log
  grep -E "Running com\." "$AFFECTED_LOG" | sed 's/.*Running /  /' || true
fi
echo

SELECTED_COUNT=$(grep -c "Running com\." "$AFFECTED_LOG" 2>/dev/null || echo "?")
info "Selected: ${BOLD}${SELECTED_COUNT}${RESET} test classes (out of 112 in cds4j-core)"
info "Skipped:  $(( 112 - ${SELECTED_COUNT:-0} )) test classes"
pause 1

# ── Did the tests catch the bug? ──────────────────────────────────────────────
banner "Step 7: Did test-order catch the bug?"
if grep -qE "FAILURE|BUILD FAILURE|Tests in error:" "$AFFECTED_LOG" 2>/dev/null; then
  ok "Bug CAUGHT! The affected tests failed as expected."
  echo
  step "Failing test output:"
  grep -A5 "FAILURE\|AssertionError\|EmptyResult\|NonUnique" "$AFFECTED_LOG" 2>/dev/null | head -20 || true
else
  warn "Build status unclear — check $AFFECTED_LOG for details"
fi
pause 1

# ── Restore the patch ─────────────────────────────────────────────────────────
banner "Step 8: Restore clean state"
typecmd "cd '$CDS4J_DIR' && patch -p1 -R < '$PATCH_FILE'"
ok "ResultImpl.java restored to original"
echo
typecmd "git diff --stat cds4j-core/src/main/java/com/sap/cds/impl/ResultImpl.java"
pause 1

# ── Summary ───────────────────────────────────────────────────────────────────
banner "Summary"
echo -e "  ${BOLD}Without test-order:${RESET} run all 1,491 tests in cds4j-core"
echo -e "  ${BOLD}With test-order:${RESET}    run only ${GREEN}${BOLD}${SELECTED_COUNT:-~15}${RESET} affected tests"
echo
echo -e "  test-order uses bytecode-level dependency tracking:"
echo -e "  - No source analysis, no annotation scanning"
echo -e "  - Captures the actual classes each test touched at runtime"
echo -e "  - Works with any JVM framework (Spring, CDI, plain JUnit)"
echo
echo -e "  ${GREEN}${BOLD}Result: same bug detection, fraction of the compute cost.${RESET}"
echo
