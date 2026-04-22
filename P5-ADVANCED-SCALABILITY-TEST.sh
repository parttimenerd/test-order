#!/bin/bash
set -e

REPO_DIR="/Users/i560383_1/code/experiments/test-order"
RESULTS_DIR="${REPO_DIR}/p5-scalability-results"
mkdir -p "$RESULTS_DIR"

# Test function that measures performance and captures errors
run_scalability_test() {
    local TEST_NAME=$1
    local NUM_CLASSES=$2
    local METHODS=$3
    
    TOTAL_TESTS=$((NUM_CLASSES * METHODS))
    TEST_DIR="${RESULTS_DIR}/${TEST_NAME}"
    TEST_LOG="${RESULTS_DIR}/${TEST_NAME}.log"
    TEST_METRICS="${RESULTS_DIR}/${TEST_NAME}-metrics.txt"
    
    echo "[$(date '+%H:%M:%S')] Testing: ${TEST_NAME} (${NUM_CLASSES} classes, ${TOTAL_TESTS} tests)..."
    
    # Generate project if not exists
    if [ ! -d "$TEST_DIR" ]; then
        echo "  Generating ${TOTAL_TESTS} tests..."
        python3 "${REPO_DIR}/scripts/generate_large_test_project.py" "$TEST_DIR" "$NUM_CLASSES" "$METHODS" > /dev/null 2>&1
    fi
    
    cd "$TEST_DIR"
    
    # Capture baseline metrics
    START_TIME=$(date +%s%N)
    
    # Run test and capture all output
    if timeout 1200 mvn -q clean test > "$TEST_LOG" 2>&1; then
        END_TIME=$(date +%s%N)
        DURATION=$(echo "scale=2; ($END_TIME - $START_TIME) / 1000000000" | bc)
        
        # Extract test results
        TESTS_RUN=$(grep -i "tests run:" "$TEST_LOG" | head -1 || echo "unknown")
        FAILURES=$(grep -i "failures:" "$TEST_LOG" | head -1 || echo "0")
        ERRORS=$(grep -i "errors:" "$TEST_LOG" | head -1 || echo "0")
        
        echo "  ✓ SUCCESS in ${DURATION}s"
        echo "${TEST_NAME},PASS,${TOTAL_TESTS},${DURATION}" >> "${RESULTS_DIR}/summary.csv"
        
        # Save metrics
        echo "Duration: ${DURATION}s" > "$TEST_METRICS"
        echo "Tests: ${TOTAL_TESTS}" >> "$TEST_METRICS"
        echo "$TESTS_RUN" >> "$TEST_METRICS"
    else
        EXIT_CODE=$?
        END_TIME=$(date +%s%N)
        DURATION=$(echo "scale=2; ($END_TIME - $START_TIME) / 1000000000" | bc)
        
        echo "  ✗ FAILED/TIMEOUT (exit code: $EXIT_CODE, duration: ${DURATION}s)"
        echo "${TEST_NAME},FAIL,${TOTAL_TESTS},${DURATION}" >> "${RESULTS_DIR}/summary.csv"
        
        # Capture error details
        echo "EXIT_CODE: $EXIT_CODE" > "$TEST_METRICS"
        echo "Duration: ${DURATION}s" >> "$TEST_METRICS"
        echo "=== Last 50 lines of output ===" >> "$TEST_METRICS"
        tail -50 "$TEST_LOG" >> "$TEST_METRICS"
        
        # Check for specific error patterns
        if grep -q "OutOfMemoryError" "$TEST_LOG"; then
            echo "ERROR: OutOfMemoryError detected!"
            echo "OutOfMemoryError detected" >> "$TEST_METRICS"
        fi
        
        if grep -q "StackOverflow" "$TEST_LOG"; then
            echo "ERROR: StackOverflowError detected!"
            echo "StackOverflowError detected" >> "$TEST_METRICS"
        fi
        
        if grep -q "Permission denied" "$TEST_LOG"; then
            echo "ERROR: Permission denied detected!"
            echo "Permission denied" >> "$TEST_METRICS"
        fi
    fi
}

# Initialize CSV header
echo "TestName,Status,TestCount,Duration" > "${RESULTS_DIR}/summary.csv"

echo "=========================================="
echo "Phase 5 Advanced Scalability Testing"
echo "=========================================="
echo ""

# Test escalating sizes
run_scalability_test "test-3000-classes" 3000 3  # 9000 tests
run_scalability_test "test-5000-classes" 5000 2  # 10000 tests
run_scalability_test "test-7500-classes" 7500 2  # 15000 tests
run_scalability_test "test-10000-classes" 10000 1 # 10000 tests

echo ""
echo "=========================================="
echo "Test Summary:"
echo "=========================================="
cat "${RESULTS_DIR}/summary.csv"
echo ""
