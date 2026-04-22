#!/usr/bin/env bash
# bug-hunt.sh — Walk through the test-order plugin as a normal user on spring-petclinic.
# Designed to be run inside an asciinema recording.
# Every section prints a clear banner so viewers can follow along.
set -euo pipefail

ROOT="/Users/i560383_1/code/experiments/test-order"
PC="$ROOT/spring-petclinic"
SKIP="-Dcheckstyle.skip -Dspring-javaformat.skip"
EXCLUDE='-Dtest=!*IntegrationTests,!MySqlIntegrationTests,!PostgresIntegrationTests,!MysqlTestApplication'

# helper: print a visible section banner and pause briefly
banner() {
  echo ""
  echo "╔══════════════════════════════════════════════════════════════╗"
  printf "║  %-58s  ║\n" "$1"
  echo "╚══════════════════════════════════════════════════════════════╝"
  echo ""
  sleep 1
}

# helper: run a command, show it, tee output to a log file, capture exit code
run() {
  local label="$1"; shift
  echo "▶ $label"
  echo "  \$ $*"
  local rc=0
  "$@" 2>&1 | tee -a "$ROOT/bug-hunt.log" || rc=$?
  echo "  ⇒ exit $rc"
  echo ""
  sleep 0.5
  return $rc
}

# initialise log
: > "$ROOT/bug-hunt.log"

cd "$PC"

###############################################################################
banner "PHASE 1 — Clean slate (simulate fresh user)"
###############################################################################
run "Remove old state files" rm -rf .test-order

run "Verify state is clean" ls -la .test-order/test-dependencies.lz4 .test-order/state.lz4 .test-order/hashes.lz4 2>&1 || true

run "Quick compile check" mvn compile $SKIP -q

###############################################################################
banner "PHASE 2 — Learn mode (first-time user)"
###############################################################################
run "Learn mode run" mvn test -Dtestorder.mode=learn $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

run "Check generated files" ls -lh .test-order/test-dependencies.lz4 .test-order/state.lz4 .test-order/hashes.lz4 2>&1 || true

###############################################################################
banner "PHASE 3 — Show order (inspect scoring)"
###############################################################################
run "show-order (default weights)" mvn test-order:show-order $SKIP 2>&1 || true

run "show-order (newTest=50)" mvn test-order:show-order -Dtestorder.score.newTest=50 $SKIP 2>&1 || true

###############################################################################
banner "PHASE 4 — Order mode (normal test run)"
###############################################################################
run "Run tests in order mode" mvn test -Dtestorder.mode=order $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

run "State file exists?" ls -lh .test-order/state.lz4 2>&1 || true

###############################################################################
banner "PHASE 5 — Combined mode (recommended workflow)"
###############################################################################
run "Combined run" mvn test-order:combined test $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

run "Selected tests" cat target/test-order-selected.txt 2>&1 || echo "(file missing)"

run "Remaining tests" cat target/test-order-remaining.txt 2>&1 || echo "(file missing)"

run "Run remaining" mvn test-order:run-remaining test $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

###############################################################################
banner "PHASE 6 — Change detection"
###############################################################################
OWNER="$PC/src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"
run "Touch a source file" bash -c "echo '// test-order bug-hunt probe' >> '$OWNER'"

run "show-order after change" mvn test-order:show-order $SKIP 2>&1 || true

run "Combined after change" mvn test-order:combined test $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

# revert the probe edit
run "Revert source edit" git checkout -- "$OWNER"

###############################################################################
banner "PHASE 7 — Dashboard"
###############################################################################
run "Generate dashboard" mvn test-order:dashboard $SKIP 2>&1 || true

run "Check dashboard file" ls -lh target/test-order-dashboard/index.html 2>&1 || true

###############################################################################
banner "PHASE 8 — Dump index"
###############################################################################
run "Dump index (first 40 lines)" bash -c "mvn test-order:dump $SKIP 2>&1 | head -40" || true

###############################################################################
banner "PHASE 9 — Edge cases"
###############################################################################

# 9a: missing index
run "Delete index" rm -f .test-order/test-dependencies.lz4
run "show-order with no index" mvn test-order:show-order $SKIP 2>&1 || true

# restore index via a quick learn
run "Re-learn to restore index" mvn test -Dtestorder.mode=learn $SKIP $EXCLUDE -q 2>&1 || true

# 9b: corrupt state file
run "Corrupt the state file" bash -c "mkdir -p .test-order && echo 'GARBAGE_CORRUPT_DATA_XYZ' > .test-order/state.lz4"
run "Test run with corrupt state" mvn test -Dtestorder.mode=order $SKIP $EXCLUDE 2>&1 | tee "$ROOT/mvn.out" || true

# 9c: all weights zero
run "All weights = 0" mvn test-order:show-order \
  -Dtestorder.score.newTest=0 \
  -Dtestorder.score.changedTest=0 \
  -Dtestorder.score.maxFailure=0 \
  -Dtestorder.score.speed=0 \
  -Dtestorder.score.depOverlap=0 \
  $SKIP 2>&1 || true

# 9d: negative weight
run "Negative weight (newTest=-5)" mvn test-order:show-order \
  -Dtestorder.score.newTest=-5 $SKIP 2>&1 || true

# 9e: invalid instrumentation mode
run "Invalid instrumentation mode" mvn test -Dtestorder.mode=learn \
  -Dtestorder.instrumentationMode=INVALID $SKIP $EXCLUDE 2>&1 | tail -30 || true

###############################################################################
banner "PHASE 10 — Optimize"
###############################################################################
run "Optimize weights" mvn test-order:optimize $SKIP 2>&1 || true

###############################################################################
banner "DONE — recording complete"
###############################################################################
echo "See $ROOT/bug-hunt.log for full output."
echo "Bugs found are noted inline above — look for ERROR, Exception, Warning, or unexpected output."
