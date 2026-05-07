#!/usr/bin/env bash
set -euo pipefail

# Find order-dependent test bugs in test-order by running tests in different orders.
#
# Strategy:
#   1. Run tests in default order (baseline — must pass)
#   2. Run tests in reverse class order (catches forward dependencies)
#   3. Run tests in random order (multiple seeds to catch intermittent deps)
#
# Targets: test-order-core (30 tests) and test-order-maven-plugin unit tests (15 tests)
#
# Usage:
#   ./scripts/find_order_dependent_bugs.sh              # all modules
#   ./scripts/find_order_dependent_bugs.sh core         # test-order-core only
#   ./scripts/find_order_dependent_bugs.sh plugin       # test-order-maven-plugin only

cd "$(dirname "$0")/.."

MODULES_CORE="test-order-core"
MODULES_PLUGIN="test-order-maven-plugin"
SKIP_FLAGS="-Dspotless.check.skip=true -DfailIfNoTests=false"
RESULTS_DIR="target/order-dependent-results"
SEEDS=(42 123 999 7 2024)
FOUND_BUGS=()

mkdir -p "$RESULTS_DIR"

log() { echo "$(date +%H:%M:%S) [order-bug-finder] $*"; }

run_tests() {
  local label="$1"
  local module="$2"
  shift 2
  local extra_args=("$@")

  local outfile="$RESULTS_DIR/${module//\//-}_${label}.txt"
  log "Running: $label on $module"

  if mvn test -pl "$module" $SKIP_FLAGS "${extra_args[@]}" > "$outfile" 2>&1; then
    log "  PASS: $label"
    return 0
  else
    log "  FAIL: $label  ← potential order-dependent bug!"
    # Extract failure info
    grep -A 5 "<<< FAILURE!" "$outfile" 2>/dev/null | head -30 >> "$RESULTS_DIR/failures.txt" || true
    grep "Tests run:" "$outfile" | tail -5 >> "$RESULTS_DIR/failures.txt" || true
    echo "---" >> "$RESULTS_DIR/failures.txt"
    FOUND_BUGS+=("$label ($module)")
    return 1
  fi
}

run_module() {
  local module="$1"
  log "=== Testing module: $module ==="

  # 1. Baseline: default order (must pass)
  if ! run_tests "baseline-default-order" "$module"; then
    log "ERROR: Baseline (default order) fails! Fix normal test failures first."
    return 1
  fi

  # 2. Reverse class order
  run_tests "reverse-class-order" "$module" \
    "-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$ClassName" \
    "-Dsurefire.runOrder=reversealphabetical" || true

  # 3. Random class order with multiple seeds
  for seed in "${SEEDS[@]}"; do
    run_tests "random-seed-$seed" "$module" \
      "-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$Random" \
      "-Djunit.jupiter.testclass.order.random.seed=$seed" \
      "-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer\$Random" \
      "-Djunit.jupiter.testmethod.order.random.seed=$seed" \
      "-Dsurefire.runOrder=random" || true
  done

  # 4. Alphabetical (different from default)
  run_tests "alphabetical-order" "$module" \
    "-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$ClassName" \
    "-Dsurefire.runOrder=alphabetical" || true
}

# Determine which modules to test
target="${1:-all}"

> "$RESULTS_DIR/failures.txt"  # clear previous

case "$target" in
  core)   run_module "$MODULES_CORE" ;;
  plugin) run_module "$MODULES_PLUGIN" ;;
  all)
    run_module "$MODULES_CORE"
    run_module "$MODULES_PLUGIN"
    ;;
  *)
    echo "Unknown target: $target (use core, plugin, or all)" >&2
    exit 1
    ;;
esac

# Summary
echo ""
log "=== SUMMARY ==="
if [ ${#FOUND_BUGS[@]} -eq 0 ]; then
  log "No order-dependent bugs found! All orderings passed."
else
  log "Found ${#FOUND_BUGS[@]} potential order-dependent bug(s):"
  for bug in "${FOUND_BUGS[@]}"; do
    log "  - $bug"
  done
  log ""
  log "Details in: $RESULTS_DIR/failures.txt"
  log "Full logs in: $RESULTS_DIR/"
  exit 1
fi
