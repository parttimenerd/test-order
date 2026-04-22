#!/bin/bash

BASE="/Users/i560383_1/code/experiments/test-order"

echo "P6-001: JUnit 4 + JUnit 5 Test Execution"
echo "=========================================="
cd "$BASE/p6-mixed-junit4-junit5"
echo "Running tests..."
mvn clean test 2>&1 | tail -20
echo ""

echo "P6-002: JUnit + TestNG Test Execution"
echo "=========================================="
cd "$BASE/p6-mixed-junit-testng"
echo "Running tests..."
mvn clean test 2>&1 | tail -20
echo ""

echo "P6-003: All Three Frameworks"
echo "=========================================="
cd "$BASE/p6-mixed-all-three"
echo "Running tests..."
mvn clean test 2>&1 | tail -20

