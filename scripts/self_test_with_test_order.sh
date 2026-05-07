#!/usr/bin/env bash
set -euo pipefail

# Use the test-order plugin on itself to find bugs through intelligent test ordering.
#
# This script:
#   1. Installs the plugin locally (if needed)
#   2. Runs learn mode on test-order-core to build dependency index
#   3. Runs order mode to reorder tests by dependency overlap
#   4. Then runs multiple random permutations to find order-dependent failures
#
# Prerequisites: Java 17+, Maven 3.9+
#
# Usage:
#   ./scripts/self_test_with_test_order.sh

cd "$(dirname "$0")/.."

MODULE="test-order-core"
SKIP="-Dspotless.check.skip=true"
RESULTS_DIR="target/self-test-results"

mkdir -p "$RESULTS_DIR"

log() { echo "$(date +%H:%M:%S) [self-test] $*"; }

# Step 1: Ensure plugin is installed
log "Step 1: Installing test-order plugin locally..."
mvn install -DskipTests $SKIP -q 2>&1 | tail -3
log "  Done."

# Step 2: Run learn mode on test-order-core
log "Step 2: Running learn mode on $MODULE..."
mvn test -pl $MODULE $SKIP \
  -Dtestorder.mode=learn \
  -Dtestorder.includePackages=me.bechberger.testorder \
  > "$RESULTS_DIR/learn-run.txt" 2>&1 || {
    log "  WARN: Learn mode had test failures (expected if there are real bugs)"
  }

# Check if dependency index was created
INDEX_FILE="$MODULE/.test-order/test-dependencies.lz4"
if [ -f "$INDEX_FILE" ]; then
  log "  Dependency index created: $INDEX_FILE"
else
  log "  WARN: No dependency index found at $INDEX_FILE"
  log "  Dependency index not found. Continuing with random order tests only."
fi

# Step 3: Run in order mode (tests reordered by dependency overlap)
log "Step 3: Running in order mode..."
mvn test -pl $MODULE $SKIP \
  -Dtestorder.mode=order \
  > "$RESULTS_DIR/order-run.txt" 2>&1 && {
    log "  PASS: Order mode"
  } || {
    log "  FAIL: Order mode detected failures!"
    grep -A 3 "<<< FAILURE!" "$RESULTS_DIR/order-run.txt" | head -20
  }

# Step 4: Isolation test — run each test class alone, then together in pairs
log "Step 4: Running isolation tests to find shared-state bugs..."

# Get list of test classes
TEST_CLASSES=$(find "$MODULE/src/test" -name "*Test.java" -exec basename {} .java \; | sort)
FAILED_PAIRS=()

log "  Found $(echo "$TEST_CLASSES" | wc -l | tr -d ' ') test classes"

# Run each test class in isolation first to establish baseline
for tc in $TEST_CLASSES; do
  if ! mvn test -pl $MODULE $SKIP -Dtest="$tc" -q > /dev/null 2>&1; then
    log "  WARN: $tc fails even in isolation — skipping from pair tests"
    TEST_CLASSES=$(echo "$TEST_CLASSES" | grep -v "^${tc}$")
  fi
done

# Run suspicious pairs (tests that share state through static fields, temp dirs, etc.)
SUSPICIOUS_PAIRS=(
  "TestOrderStateTest,PersistenceSupportTest"
  "DependencyMapTest,ClassNameTrieTest"
  "FileHashStoreTest,ChangeDetectorTest"
  "DurationTrackerTest,FailureHistoryTrackerTest"
  "TestSelectorTest,DepsAndScoringTest"
  "StructuralDiffTest,StructuralChangeAnalyzerTest"
  "GitChangeDetectorTest,ChangeDetectionSupportTest"
)

for pair in "${SUSPICIOUS_PAIRS[@]}"; do
  # Forward order
  if ! mvn test -pl $MODULE $SKIP -Dtest="$pair" -q > /dev/null 2>&1; then
    # Check if reverse also fails
    reversed=$(echo "$pair" | awk -F',' '{print $2","$1}')
    if mvn test -pl $MODULE $SKIP -Dtest="$reversed" -q > /dev/null 2>&1; then
      log "  ORDER-DEPENDENT: $pair fails, but $reversed passes!"
      FAILED_PAIRS+=("$pair")
    else
      log "  BOTH-FAIL: $pair (may be a real bug, not order-dependent)"
    fi
  fi
done

# Step 5: Random full-suite runs
log "Step 5: Running random order permutations..."
SEEDS=(1 42 100 256 999 1234 7777 9999 31415 65536)
RANDOM_FAILURES=0

for seed in "${SEEDS[@]}"; do
  if ! mvn test -pl $MODULE $SKIP \
    -Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$Random \
    -Djunit.jupiter.testclass.order.random.seed=$seed \
    -Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer\$Random \
    -Djunit.jupiter.testmethod.order.random.seed=$seed \
    > "$RESULTS_DIR/random-seed-$seed.txt" 2>&1; then
    log "  FAIL with seed=$seed"
    RANDOM_FAILURES=$((RANDOM_FAILURES + 1))
    grep "<<< FAILURE!" "$RESULTS_DIR/random-seed-$seed.txt" | head -5 >> "$RESULTS_DIR/random-failures.txt" || true
  fi
done

# Summary
echo ""
log "=== RESULTS SUMMARY ==="
log "Random order failures: $RANDOM_FAILURES / ${#SEEDS[@]} seeds"
log "Order-dependent pairs: ${#FAILED_PAIRS[@]}"

if [ ${#FAILED_PAIRS[@]} -gt 0 ]; then
  log ""
  log "Order-dependent pairs found:"
  for p in "${FAILED_PAIRS[@]}"; do
    log "  - $p"
  done
fi

if [ $RANDOM_FAILURES -gt 0 ]; then
  log ""
  log "Random failures logged to: $RESULTS_DIR/random-failures.txt"
fi

log ""
log "Full logs in: $RESULTS_DIR/"

[ ${#FAILED_PAIRS[@]} -eq 0 ] && [ $RANDOM_FAILURES -eq 0 ] && {
  log "No order-dependent bugs found!"
  exit 0
}
exit 1
