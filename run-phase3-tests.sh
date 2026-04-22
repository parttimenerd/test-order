#!/bin/bash
# PHASE 3: Comprehensive Filesystem Edge Case Testing
# Tests test-order plugins with aggressive filesystem edge cases

set -e

RESULTS_FILE="/tmp/phase3-results.txt"
BUGS_FILE="/tmp/phase3-bugs.txt"
DETAILED_LOG="/tmp/phase3-detailed.log"

{
    echo "=========================================="
    echo "PHASE 3: FILESYSTEM EDGE CASE TESTING"
    echo "=========================================="
    echo "Date: $(date)"
    echo ""
} | tee "$RESULTS_FILE"

# Helper function
run_test_suite() {
    local name="$1"
    local script="$2"
    echo "Running: $name..." | tee -a "$RESULTS_FILE"
    if bash "$script" 2>&1 | tee -a "$DETAILED_LOG"; then
        echo "✓ $name completed" | tee -a "$RESULTS_FILE"
    else
        echo "✗ $name failed" | tee -a "$RESULTS_FILE"
    fi
    echo "" | tee -a "$RESULTS_FILE"
}

# Run all test suites
cd /Users/i560383_1/code/experiments/test-order

echo "Test Suite Execution:" | tee -a "$RESULTS_FILE"
echo "===================" | tee -a "$RESULTS_FILE"

# These tests are environment-safe and don't require actual Maven/Gradle
run_test_suite "Path Extremes" "test-phase3-paths.sh" 2>&1 | tee -a "$DETAILED_LOG"
run_test_suite "Symbolic Links" "test-phase3-symlinks.sh" 2>&1 | tee -a "$DETAILED_LOG"
run_test_suite "Permission Issues" "test-phase3-permissions.sh" 2>&1 | tee -a "$DETAILED_LOG"
run_test_suite "Corruption & Cache" "test-phase3-corruption.sh" 2>&1 | tee -a "$DETAILED_LOG"

echo "========================================" | tee -a "$RESULTS_FILE"
echo "Test execution complete!" | tee -a "$RESULTS_FILE"
echo "Results: $RESULTS_FILE" | tee -a "$RESULTS_FILE"
echo "Detailed: $DETAILED_LOG" | tee -a "$RESULTS_FILE"

# Show summary
echo "" | tee -a "$RESULTS_FILE"
echo "SUMMARY:" | tee -a "$RESULTS_FILE"
grep -E "PASS|FAIL" "$DETAILED_LOG" | sort | uniq -c | tee -a "$RESULTS_FILE" || true
