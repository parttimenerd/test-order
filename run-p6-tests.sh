#!/bin/bash
set -e

BASE_DIR="/Users/i560383_1/code/experiments/test-order"
RESULTS_DIR="$BASE_DIR/p6-results"
mkdir -p "$RESULTS_DIR"

# Test each project
echo "=========================================="
echo "P6-001: JUnit 4 + JUnit 5 Mixed"
echo "=========================================="
cd "$BASE_DIR/p6-mixed-junit4-junit5"
mvn clean test > "$RESULTS_DIR/p6-001-junit4-junit5.log" 2>&1
TEST_COUNT=$(grep -c "Tests run:" "$RESULTS_DIR/p6-001-junit4-junit5.log" || echo "0")
cat "$RESULTS_DIR/p6-001-junit4-junit5.log" | grep -A 5 "T E S T S" | head -20

echo ""
echo "=========================================="
echo "P6-002: JUnit + TestNG (Unsupported)"
echo "=========================================="
cd "$BASE_DIR/p6-mixed-junit-testng"
mvn clean test > "$RESULTS_DIR/p6-002-junit-testng.log" 2>&1
cat "$RESULTS_DIR/p6-002-junit-testng.log" | grep -A 5 "T E S T S" | head -20 || echo "No tests executed"

echo ""
echo "=========================================="
echo "P6-003: All Three Frameworks (JUnit4/5 + TestNG)"
echo "=========================================="
cd "$BASE_DIR/p6-mixed-all-three"
mvn clean test > "$RESULTS_DIR/p6-003-all-three.log" 2>&1
cat "$RESULTS_DIR/p6-003-all-three.log" | grep -A 5 "T E S T S" | head -20 || echo "No tests executed"

echo ""
echo "=========================================="
echo "P6 Test Results Summary"
echo "=========================================="
ls -lah "$RESULTS_DIR/"

