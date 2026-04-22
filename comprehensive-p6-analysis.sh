#!/bin/bash

BASE_DIR="/Users/i560383_1/code/experiments/test-order"
RESULTS="$BASE_DIR/p6-results"
mkdir -p "$RESULTS"

run_test_analysis() {
    local PROJECT=$1
    local PROJECT_NAME=$2
    local DESCRIPTION=$3
    
    echo ""
    echo "============================================"
    echo "Testing: $DESCRIPTION"
    echo "============================================"
    
    cd "$BASE_DIR/$PROJECT"
    
    # Run without test-order first
    echo "→ Running WITHOUT test-order plugin..."
    mvn clean test -DskipTests=false 2>&1 | grep -E "(Tests run:|Running|BUILD|ERROR)" > "$RESULTS/${PROJECT_NAME}-base.txt"
    
    # Count tests
    BASE_COUNT=$(grep "Tests run:" "$RESULTS/${PROJECT_NAME}-base.txt" | wc -l)
    BASE_TESTS=$(grep "Tests run:" "$RESULTS/${PROJECT_NAME}-base.txt" | tail -1 | grep -oP 'Tests run: \K[0-9]+')
    
    echo "  Tests found: ${BASE_TESTS:-0}"
    
    # Run with test-order plugin
    echo "→ Running WITH test-order plugin..."
    mvn clean test -Dtest-order.enabled=true 2>&1 | grep -E "(Tests run:|Running|BUILD|ERROR)" > "$RESULTS/${PROJECT_NAME}-with-plugin.txt"
    
    # Extract key findings
    echo "  Base run result:"
    tail -3 "$RESULTS/${PROJECT_NAME}-base.txt" | head -1
    echo "  With plugin result:"
    tail -3 "$RESULTS/${PROJECT_NAME}-with-plugin.txt" | head -1
}

# Test all projects
run_test_analysis "p6-mixed-junit4-junit5" "p6-001" "JUnit 4 + JUnit 5"
run_test_analysis "p6-mixed-junit-testng" "p6-002" "JUnit + TestNG"
run_test_analysis "p6-mixed-all-three" "p6-003" "JUnit4 + JUnit5 + TestNG"

# Analyze results
echo ""
echo "============================================"
echo "FINDINGS SUMMARY"
echo "============================================"

for f in "$RESULTS"/*.txt; do
    echo "$(basename $f):"
    tail -3 "$f" | head -1
done

