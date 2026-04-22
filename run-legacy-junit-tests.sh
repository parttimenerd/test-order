#!/bin/bash

LOG_FILE="/Users/i560383_1/code/experiments/test-order/P5-LEGACY-JUNIT-TEST-RESULTS.txt"
echo "=== Phase 5 Legacy JUnit Version Testing ===" > "$LOG_FILE"
echo "Started: $(date)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

test_project() {
    local proj_dir=$1
    local proj_name=$2
    
    echo "Testing: $proj_name" 
    echo "Testing: $proj_name" >> "$LOG_FILE"
    
    cd "$proj_dir"
    
    # Run the test
    output=$(mvn clean test 2>&1)
    
    if echo "$output" | grep -q "BUILD SUCCESS"; then
        echo "✓ PASS" 
        echo "✓ PASS" >> "$LOG_FILE"
        return 0
    else
        echo "✗ FAIL"
        echo "✗ FAIL" >> "$LOG_FILE"
        # Extract error details
        error=$(echo "$output" | grep -A 5 "ERROR\|FAIL" | head -20)
        echo "Error details:" >> "$LOG_FILE"
        echo "$error" >> "$LOG_FILE"
        return 1
    fi
}

# Test all projects
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-old-test" "JUnit 4.12 Basic"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit5-old-test" "JUnit 5.5 Basic"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-categories-test" "JUnit 4 Categories"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-parameterized-test" "JUnit 4 Parameterized"
test_project "/Users/i560383_1/code/experiments/test-order/p5-junit4-rules-test" "JUnit 4 Rules"
test_project "/Users/i560383_1/code/experiments/test-order/p5-mixed-junit-test" "Mixed JUnit 4 + 5"

echo "" >> "$LOG_FILE"
echo "Completed: $(date)" >> "$LOG_FILE"

cat "$LOG_FILE"
