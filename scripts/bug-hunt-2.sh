#!/usr/bin/env bash
# Bug Hunt #2 — test-order plugin
# Focus: select/run-remaining workflow, snapshot, aggregate, Kotlin example,
#         JUnit 5 example, Gradle example, dashboard serve, change detection modes,
#         edge cases around multi-run lifecycle, coverage goal (if exists)
set -euo pipefail

ROOT="/Users/i560383_1/code/experiments/test-order"
SKIP="-Dcheckstyle.skip -Dspring-javaformat.skip"
LOG="$ROOT/bug-hunt-2.log"
> "$LOG"

log() { echo -e "\n\033[1;36m══════ $1 ══════\033[0m"; }

exec > >(tee -a "$LOG") 2>&1

# ────────────────────────────────────────────────────────
# Phase 1: test-order-example — select + run-remaining flow
# ────────────────────────────────────────────────────────
log "Phase 1: Select + Run-Remaining on test-order-example"
cd "$ROOT/test-order-example"

# Clean prior state to start fresh
rm -rf .test-order

# Learn mode first
log "Phase 1a: Learn mode"
mvn test -Dtestorder.mode=learn -q 2>&1 || true
ls -la .test-order/test-dependencies.lz4 .test-order/state.lz4 2>&1 || echo "MISSING STATE FILES"

# Show order - should work since we just learned
log "Phase 1b: Show-order after learn"
mvn test-order:show-order 2>&1 || true

# Select mode
log "Phase 1c: Select mode (topN=1, randomM=0)"
mvn test-order:select test -Dtestorder.select-top-n=1 -Dtestorder.select-random-m=0 -q 2>&1 || true
echo "--- selected.txt ---"
cat target/test-order-selected.txt 2>&1 || echo "NO SELECTED FILE"
echo "--- remaining.txt ---"
cat target/test-order-remaining.txt 2>&1 || echo "NO REMAINING FILE"

# Run-remaining
log "Phase 1d: Run-remaining"
mvn test-order:run-remaining test -q 2>&1 || true

# Run-remaining again (should be no-op — file consumed or empty?)
log "Phase 1e: Run-remaining second time (should be no-op or graceful)"
mvn test-order:run-remaining test -q 2>&1 || true

# Select with seed for reproducibility
log "Phase 1f: Select with seed=42"
mvn test-order:select test -Dtestorder.select-top-n=1 -Dtestorder.select-random-m=1 -Dtestorder.select-seed=42 -q 2>&1 || true
echo "--- selected.txt (seeded) ---"
cat target/test-order-selected.txt 2>&1 || echo "NO SELECTED FILE"

# Select again with same seed — should be identical
log "Phase 1g: Select with same seed=42 (should be identical)"
mvn test-order:select test -Dtestorder.select-top-n=1 -Dtestorder.select-random-m=1 -Dtestorder.select-seed=42 -q 2>&1 || true
echo "--- selected.txt (seeded again) ---"
cat target/test-order-selected.txt 2>&1 || echo "NO SELECTED FILE"

# ────────────────────────────────────────────────────────
# Phase 2: Snapshot + Aggregate workflow
# ────────────────────────────────────────────────────────
log "Phase 2: Snapshot + Aggregate"
cd "$ROOT/test-order-example"

# Take a snapshot
log "Phase 2a: Snapshot"
mvn test-order:snapshot 2>&1 || true
ls -la .test-order/hashes.lz4 .test-order/test-hashes.lz4 2>&1 || echo "MISSING HASH FILES"

# Delete index, try aggregate from deps dir
log "Phase 2b: Aggregate (with deps files from learn mode)"
rm -f .test-order/test-dependencies.lz4
mvn test-order:aggregate 2>&1 || true
ls -la .test-order/test-dependencies.lz4 2>&1 || echo "INDEX NOT CREATED BY AGGREGATE"

# Aggregate when no deps dir exists
log "Phase 2c: Aggregate with no deps dir (should error gracefully)"
rm -rf target/test-order-deps 2>/dev/null || true
mvn test-order:aggregate 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 3: Dump mode
# ────────────────────────────────────────────────────────
log "Phase 3: Dump"
cd "$ROOT/test-order-example"
mvn test-order:dump 2>&1 | head -60 || true

# Dump with no index
log "Phase 3b: Dump with no index (should error gracefully)"
rm -f .test-order/test-dependencies.lz4
mvn test-order:dump 2>&1 || true

# Restore index for later tests
mvn test -Dtestorder.mode=learn -q 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 4: test-order-example-junit5 (JUnit 5 compat)
# ────────────────────────────────────────────────────────
log "Phase 4: JUnit 5 example"
cd "$ROOT/test-order-example-junit5"
rm -rf .test-order

log "Phase 4a: Combined on JUnit 5"
mvn test-order:combined test -q 2>&1 || true
ls -la .test-order/test-dependencies.lz4 .test-order/state.lz4 2>&1 || echo "MISSING STATE FILES"

log "Phase 4b: Show-order JUnit 5"
mvn test-order:show-order 2>&1 || true

log "Phase 4c: Dashboard JUnit 5"
mvn test-order:dashboard 2>&1 || true
ls -la target/test-order-dashboard/index.html 2>&1 || echo "NO DASHBOARD"

# ────────────────────────────────────────────────────────
# Phase 5: Kotlin example
# ────────────────────────────────────────────────────────
log "Phase 5: Kotlin example"
cd "$ROOT/test-order-example-kotlin"
rm -rf .test-order

log "Phase 5a: Combined on Kotlin"
mvn test-order:combined test -q 2>&1 || true
ls -la .test-order/test-dependencies.lz4 .test-order/state.lz4 2>&1 || echo "MISSING STATE FILES"

log "Phase 5b: Show-order Kotlin"
mvn test-order:show-order 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 6: Gradle example
# ────────────────────────────────────────────────────────
log "Phase 6: Gradle example"
cd "$ROOT/test-order-example-gradle"
rm -rf .test-order

log "Phase 6a: Gradle test (learn mode)"
./gradlew test --no-daemon -Dtestorder.mode=learn 2>&1 | tail -30 || true
ls -la .test-order/test-dependencies.lz4 .test-order/state.lz4 2>&1 || echo "MISSING STATE FILES"

log "Phase 6b: Gradle test (order mode)"
./gradlew test --no-daemon 2>&1 | tail -30 || true

# ────────────────────────────────────────────────────────
# Phase 7: Change detection modes
# ────────────────────────────────────────────────────────
log "Phase 7: Change detection modes"
cd "$ROOT/test-order-example"

log "Phase 7a: since-last-commit"
mvn test-order:show-order -Dtestorder.change-mode=since-last-commit 2>&1 || true

log "Phase 7b: since-last-run"
mvn test-order:show-order -Dtestorder.change-mode=since-last-run 2>&1 || true

log "Phase 7c: uncommitted"
mvn test-order:show-order -Dtestorder.change-mode=uncommitted 2>&1 || true

log "Phase 7d: explicit with changedClasses"
mvn test-order:show-order -Dtestorder.change-mode=explicit -Dtestorder.changedClasses=com.example.app.Calculator 2>&1 || true

log "Phase 7e: explicit with empty changedClasses"
mvn test-order:show-order -Dtestorder.change-mode=explicit -Dtestorder.changedClasses= 2>&1 || true

log "Phase 7f: invalid change mode"
mvn test-order:show-order -Dtestorder.change-mode=BOGUS 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 8: Dashboard serve (basic check)
# ────────────────────────────────────────────────────────
log "Phase 8: Dashboard serve"
cd "$ROOT/test-order-example"

log "Phase 8a: Serve with auto port (kill after 3 seconds)"
timeout 5 mvn test-order:serve 2>&1 || true

log "Phase 8b: Serve with explicit port"
timeout 5 mvn test-order:serve -Dtestorder.dashboard.port=38923 2>&1 || true

log "Phase 8c: Serve with regenerate=never and no dashboard file"
rm -rf target/test-order-dashboard
timeout 5 mvn test-order:serve -Dtestorder.dashboard.regenerate=never 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 9: Edge cases
# ────────────────────────────────────────────────────────
log "Phase 9: Edge cases"
cd "$ROOT/test-order-example"

# Restore learn data
mvn test -Dtestorder.mode=learn -q 2>&1 || true

log "Phase 9a: All weights at maximum (100 each)"
mvn test-order:show-order -Dtestorder.score.newTest=100 -Dtestorder.score.changedTest=100 -Dtestorder.score.maxFailure=100 -Dtestorder.score.speed=100 -Dtestorder.score.depOverlap=100 -Dtestorder.score.speedPenalty=100 -Dtestorder.score.changeComplexity=100 2>&1 || true

log "Phase 9b: Select with topN=0 randomM=0 (nothing selected?)"
mvn test-order:select test -Dtestorder.select-top-n=0 -Dtestorder.select-random-m=0 2>&1 || true
echo "--- selected.txt ---"
cat target/test-order-selected.txt 2>&1 || echo "NO FILE"
echo "--- remaining.txt ---"
cat target/test-order-remaining.txt 2>&1 || echo "NO FILE"

log "Phase 9c: Select with topN=999 (more than total tests)"
mvn test-order:select test -Dtestorder.select-top-n=999 -Dtestorder.select-random-m=999 2>&1 || true
echo "--- selected.txt ---"
cat target/test-order-selected.txt 2>&1 || echo "NO FILE"

log "Phase 9d: Run combined twice in a row"
mvn test-order:combined test -q 2>&1 || true
mvn test-order:combined test -q 2>&1 || true
echo "--- Test runs in state ---"
mvn test-order:show-order 2>&1 | head -30 || true

log "Phase 9e: Optimize with only 1 run (insufficient history)"
rm -rf .test-order
mvn test -Dtestorder.mode=learn -q 2>&1 || true
mvn test-order:optimize 2>&1 || true

log "Phase 9f: Combined with includePackages filter"
mvn test-order:combined test -Dtestorder.include-packages=com.example.nonexistent -q 2>&1 || true

log "Phase 9g: Show-order with non-existent index path"
mvn test-order:show-order -Dtestorder.index.path=/tmp/nonexistent.lz4 2>&1 || true

log "Phase 9h: Prepare goal directly"
mvn test-order:prepare 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 10: Multi-run lifecycle (learn → change → combined → dashboard)
# ────────────────────────────────────────────────────────
log "Phase 10: Multi-run lifecycle"
cd "$ROOT/test-order-example"
rm -rf .test-order

log "Phase 10a: Learn mode (run 1)"
mvn test -Dtestorder.mode=learn -q 2>&1 || true

log "Phase 10b: Combined (run 2)"
mvn test-order:combined test -q 2>&1 || true

log "Phase 10c: Make a source change"
echo "// bug-hunt-2 change marker" >> src/main/java/com/example/app/Calculator.java

log "Phase 10d: Combined after change (run 3)"
mvn test-order:combined test -q 2>&1 || true

log "Phase 10e: Show-order after change — should have changed scores"
mvn test-order:show-order 2>&1 || true

log "Phase 10f: Revert source change"
cd "$ROOT/test-order-example"
git checkout -- src/main/java/com/example/app/Calculator.java 2>/dev/null || sed -i '' '/bug-hunt-2 change marker/d' src/main/java/com/example/app/Calculator.java

log "Phase 10g: Dashboard after 3 runs"
mvn test-order:dashboard 2>&1 || true
ls -la target/test-order-dashboard/index.html 2>&1 || echo "NO DASHBOARD"

# Quick check if dashboard HTML is non-trivial
wc -c target/test-order-dashboard/index.html 2>&1 || true

# ────────────────────────────────────────────────────────
# Phase 11: spring-petclinic (revisit with new goals)
# ────────────────────────────────────────────────────────
log "Phase 11: spring-petclinic revisit"
cd "$ROOT/spring-petclinic"

log "Phase 11a: Select + run-remaining on petclinic"
mvn test-order:select test $SKIP -Dtestorder.select-top-n=3 -Dtestorder.select-random-m=2 -Dtest='!*IntegrationTests,!MySqlIntegrationTests,!PostgresIntegrationTests,!MysqlTestApplication' 2>&1 | tail -20 || true
echo "--- petclinic selected ---"
cat target/test-order-selected.txt 2>&1 || echo "NO FILE"
echo "--- petclinic remaining ---"
cat target/test-order-remaining.txt 2>&1 || echo "NO FILE"

mvn test-order:run-remaining test $SKIP -Dtest='!*IntegrationTests,!MySqlIntegrationTests,!PostgresIntegrationTests,!MysqlTestApplication' 2>&1 | tail -20 || true

log "Phase 11b: Snapshot on petclinic"
mvn test-order:snapshot $SKIP 2>&1 || true

log "Phase 11c: Dashboard serve on petclinic (3s)"
timeout 5 mvn test-order:serve $SKIP -Dtestorder.dashboard.port=38924 2>&1 || true

log "Phase 11d: Dump on petclinic"
mvn test-order:dump $SKIP 2>&1 | head -80 || true

# ────────────────────────────────────────────────────────
# Phase 12: Hostile inputs
# ────────────────────────────────────────────────────────
log "Phase 12: Hostile inputs"
cd "$ROOT/test-order-example"

log "Phase 12a: Very large topN/randomM"
mvn test-order:select test -Dtestorder.select-top-n=2147483647 -Dtestorder.select-random-m=2147483647 2>&1 || true

log "Phase 12b: Negative topN"
mvn test-order:select test -Dtestorder.select-top-n=-1 -Dtestorder.select-random-m=-1 2>&1 || true

log "Phase 12c: Non-numeric topN"
mvn test-order:select test -Dtestorder.select-top-n=abc 2>&1 || true

log "Phase 12d: Empty mode string"
mvn test -Dtestorder.mode= 2>&1 | tail -15 || true

log "Phase 12e: Unicode in changedClasses"
mvn test-order:show-order -Dtestorder.change-mode=explicit -Dtestorder.changedClasses='com.Example.Ünïcödé' 2>&1 || true

log "Phase 12f: Very long class name in changedClasses"
mvn test-order:show-order -Dtestorder.change-mode=explicit -Dtestorder.changedClasses="$(python3 -c "print('a.'*500 + 'Test')")" 2>&1 || true

log "DONE — Bug Hunt #2 complete"
echo "Full log saved to: $LOG"
