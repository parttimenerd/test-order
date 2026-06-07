#!/usr/bin/env bash
# ---------------------------------------------------------------
# Two-phase CI workflow test for samples/sample-junit6
#
# Phase 1: select → runs affected + new + @AlwaysRun tests,
#           writes remaining tests to a file.
# Phase 2: run-remaining → runs the deferred tests from the file.
#
# Prerequisites: the dependency index must already exist.
#   (run  mvn test  in the sample once first so learn mode creates it)
# ---------------------------------------------------------------
set -euo pipefail

SAMPLE_DIR="$(cd "$(dirname "$0")/../samples/sample-junit6" && pwd)"
cd "$SAMPLE_DIR"

REMAINING_FILE="target/test-order-remaining.txt"
SELECTED_FILE="target/test-order-selected.txt"

echo "=== Ensuring dependency index exists ==="
if [ ! -f .test-order/test-dependencies.lz4 ]; then
  echo "No dependency index found – running learn mode first..."
  mvn -q test -Dtestorder.mode=learn
fi

echo ""
echo "=== Phase 1: select (affected + new + @AlwaysRun) ==="
mvn -q clean test-order:affected test \
    -Dtestorder.changeMode=explicit \
    -Dtestorder.changed.classes=com.myapp.MathService

echo ""
echo "--- Selected tests (phase 1): ---"
if [ -f "$SELECTED_FILE" ]; then
  cat "$SELECTED_FILE"
  SELECTED_COUNT=$(wc -l < "$SELECTED_FILE" | tr -d ' ')
else
  echo "(file not found)"
  SELECTED_COUNT=0
fi

echo ""
echo "--- Remaining tests (deferred to phase 2): ---"
if [ -f "$REMAINING_FILE" ]; then
  cat "$REMAINING_FILE"
  REMAINING_COUNT=$(wc -l < "$REMAINING_FILE" | tr -d ' ')
else
  echo "(file not found)"
  REMAINING_COUNT=0
fi

echo ""
echo "=== Phase 2: run-remaining ==="
mvn -q test-order:run-remaining test

echo ""
echo "=== Summary ==="
echo "Phase 1 ran $SELECTED_COUNT test class(es)"
echo "Phase 2 ran $REMAINING_COUNT test class(es)"

# Verify @AlwaysRun class was selected
if [ -f "$SELECTED_FILE" ] && grep -q "SmokeTest" "$SELECTED_FILE"; then
  echo "✓ @AlwaysRun SmokeTest was included in phase 1"
else
  echo "✗ @AlwaysRun SmokeTest was NOT in phase 1 (unexpected)"
  exit 1
fi

# Verify affected test was selected
if [ -f "$SELECTED_FILE" ] && grep -q "MathService" "$SELECTED_FILE"; then
  echo "✓ Affected MathService test was included in phase 1"
else
  echo "✗ Affected MathService test was NOT in phase 1 (unexpected)"
  exit 1
fi

echo ""
echo "All checks passed."
