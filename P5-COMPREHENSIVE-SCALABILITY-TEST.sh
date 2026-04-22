#!/bin/bash
set -e

REPO_DIR="/Users/i560383_1/code/experiments/test-order"
RESULTS_DIR="${REPO_DIR}/p5-scalability-results"
mkdir -p "$RESULTS_DIR"

# Initialize results tracking
cat > "${RESULTS_DIR}/scalability-report.txt" << 'EOF'
# Phase 5 Scalability Testing Report
Generated: $(date)

## Test Results

EOF

echo "[$(date '+%H:%M:%S')] Starting Phase 5 Comprehensive Scalability Tests..."

# Test configurations: (name, num_classes, methods_per_class)
declare -a TESTS=(
    "test-100-classes:100:3"
    "test-250-classes:250:3"
    "test-500-classes:500:3"
    "test-750-classes:750:3"
    "test-1000-classes:1000:3"
    "test-2000-classes:2000:2"
)

for TEST_CONFIG in "${TESTS[@]}"; do
    IFS=':' read -r TEST_NAME NUM_CLASSES METHODS <<< "$TEST_CONFIG"
    TOTAL_TESTS=$((NUM_CLASSES * METHODS))
    
    TEST_DIR="${RESULTS_DIR}/${TEST_NAME}"
    
    echo "[$(date '+%H:%M:%S')] Testing: ${TEST_NAME} (${TOTAL_TESTS} tests)..."
    
    # Generate project if not exists
    if [ ! -d "$TEST_DIR" ]; then
        echo "  Generating project..."
        python3 "${REPO_DIR}/scripts/generate_large_test_project.py" "$TEST_DIR" "$NUM_CLASSES" "$METHODS" > /dev/null 2>&1
    fi
    
    # Run tests with timing
    cd "$TEST_DIR"
    START_TIME=$(date +%s%N)
    START_MEM=$(($(ps aux | awk '{sum+=$6} END {print sum}') / 1024))
    
    TEST_LOG="${RESULTS_DIR}/${TEST_NAME}-results.log"
    
    if timeout 900 mvn -q clean test > "$TEST_LOG" 2>&1; then
        END_TIME=$(date +%s%N)
        DURATION=$(echo "scale=2; ($END_TIME - $START_TIME) / 1000000000" | bc)
        END_MEM=$(($(ps aux | awk '{sum+=$6} END {print sum}') / 1024))
        MEMORY_USED=$((END_MEM - START_MEM))
        
        TESTS_RESULT=$(grep -i "tests run:" "$TEST_LOG" || echo "Tests run: unknown")
        
        echo "✓ PASS (${DURATION}s, ~${MEMORY_USED}MB)" | tee -a "${RESULTS_DIR}/scalability-report.txt"
        echo "  ${TEST_NAME}: PASS - ${TOTAL_TESTS} tests, Duration: ${DURATION}s, Memory: ~${MEMORY_USED}MB" >> "${RESULTS_DIR}/scalability-report.txt"
    else
        END_TIME=$(date +%s%N)
        DURATION=$(echo "scale=2; ($END_TIME - $START_TIME) / 1000000000" | bc)
        
        echo "✗ FAIL/TIMEOUT (${DURATION}s)" | tee -a "${RESULTS_DIR}/scalability-report.txt"
        echo "  ${TEST_NAME}: FAIL - Check ${TEST_LOG} for details" >> "${RESULTS_DIR}/scalability-report.txt"
        
        # Capture error
        echo "=== Error Details ===" >> "${RESULTS_DIR}/scalability-report.txt"
        tail -30 "$TEST_LOG" >> "${RESULTS_DIR}/scalability-report.txt"
        echo "" >> "${RESULTS_DIR}/scalability-report.txt"
    fi
done

echo "[$(date '+%H:%M:%S')] All tests completed!"
echo ""
echo "Results saved to: ${RESULTS_DIR}/scalability-report.txt"
echo ""
cat "${RESULTS_DIR}/scalability-report.txt"
