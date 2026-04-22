#!/bin/bash

# Comprehensive edge case testing for Phase 5

LOG_FILE="/Users/i560383_1/code/experiments/test-order/P5-EDGE-CASES-RESULTS.txt"
echo "=== Phase 5 Comprehensive Edge Case Testing ===" > "$LOG_FILE"
echo "Started: $(date)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

test_project() {
    local proj_dir=$1
    local test_name=$2
    
    echo "Testing: $test_name"
    echo "Testing: $test_name" >> "$LOG_FILE"
    
    cd "$proj_dir"
    
    if mvn clean test -q 2>&1 | grep -q "BUILD SUCCESS"; then
        echo "  ✓ PASS" 
        echo "  ✓ PASS" >> "$LOG_FILE"
        return 0
    else
        error=$(mvn clean test 2>&1 | grep -E "ERROR|Exception" | head -3)
        echo "  ✗ FAIL"
        echo "  ✗ FAIL" >> "$LOG_FILE"
        echo "  Error: $error" >> "$LOG_FILE"
        return 1
    fi
}

# Test all existing projects
echo "=== Version Compatibility ===" | tee -a "$LOG_FILE"
for ver in 4.13.2 4.12 4.11 4.10; do
    proj="/Users/i560383_1/code/experiments/test-order/p5-junit4-v${ver}-test"
    [ -d "$proj" ] && test_project "$proj" "JUnit 4.v$ver"
done

for ver in 5.10.0 5.9.0 5.5.0; do
    proj="/Users/i560383_1/code/experiments/test-order/p5-junit5-v${ver}-test"
    [ -d "$proj" ] && test_project "$proj" "JUnit 5.v$ver"
done

echo "" | tee -a "$LOG_FILE"
echo "=== Advanced Features ===" | tee -a "$LOG_FILE"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-old-test" "JUnit 4.12 Basic"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit5-old-test" "JUnit 5.5 Basic"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-categories-test" "JUnit 4 Categories"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-parameterized-test" "JUnit 4 Parameterized"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-rules-test" "JUnit 4 Rules"
test_project "/Users/i560383_1/code/experiments/test-order/p5-mixed-junit-test" "Mixed JUnit 4 + 5"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-fixmethodorder" "JUnit 4 FixMethodOrder"

echo "" | tee -a "$LOG_FILE"
echo "Completed: $(date)" | tee -a "$LOG_FILE"

cat "$LOG_FILE"
