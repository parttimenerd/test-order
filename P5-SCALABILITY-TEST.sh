#!/bin/bash
set -e

# Phase 5 Scalability Testing Script
# Tests test-order with increasingly large test suites
# Monitors: memory, CPU, time, success/failure

REPO_DIR="/Users/i560383_1/code/experiments/test-order"
TEST_RESULTS_DIR="${REPO_DIR}/p5-scalability-results"
mkdir -p "$TEST_RESULTS_DIR"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${BLUE}[$(date '+%Y-%m-%d %H:%M:%S')]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

# Test 1: Maven Multi-Module with 20 modules, 200 tests
test_maven_20_200() {
    log "Testing Maven multi-module (20 modules, 200 tests)..."
    cd "${REPO_DIR}/phase5-scale-tests/maven-reactor-20modules-200tests"
    
    local START_TIME=$(date +%s%N)
    local START_MEM=$(ps aux | grep -E '[j]ava|[m]avnw' | awk '{sum+=$6} END {print sum}' || echo 0)
    
    if timeout 300 mvn clean test -Dtest-order.enabled=true 2>&1 | tee "${TEST_RESULTS_DIR}/maven-20-200.log"; then
        success "Maven 20/200 test PASSED"
        echo "Test case: maven-20-200, status: PASS" >> "${TEST_RESULTS_DIR}/results.txt"
    else
        error "Maven 20/200 test FAILED"
        echo "Test case: maven-20-200, status: FAIL" >> "${TEST_RESULTS_DIR}/results.txt"
    fi
    
    local END_TIME=$(date +%s%N)
    local DURATION=$((($END_TIME - $START_TIME) / 1000000000))
    log "Duration: ${DURATION}s"
}

# Test 2: Maven Multi-Module with 40 modules, 300 tests
test_maven_40_300() {
    log "Testing Maven multi-module (40 modules, 300 tests)..."
    cd "${REPO_DIR}/phase5-scale-tests/maven-reactor-40modules-300tests"
    
    local START_TIME=$(date +%s%N)
    
    if timeout 300 mvn clean test -Dtest-order.enabled=true 2>&1 | tee "${TEST_RESULTS_DIR}/maven-40-300.log"; then
        success "Maven 40/300 test PASSED"
        echo "Test case: maven-40-300, status: PASS" >> "${TEST_RESULTS_DIR}/results.txt"
    else
        error "Maven 40/300 test FAILED"
        echo "Test case: maven-40-300, status: FAIL" >> "${TEST_RESULTS_DIR}/results.txt"
    fi
    
    local END_TIME=$(date +%s%N)
    local DURATION=$((($END_TIME - $START_TIME) / 1000000000))
    log "Duration: ${DURATION}s"
}

# Test 3: Maven Multi-Module with 60 modules, 500 tests
test_maven_60_500() {
    log "Testing Maven multi-module (60 modules, 500 tests)..."
    cd "${REPO_DIR}/phase5-scale-tests/maven-reactor-60modules-500tests"
    
    local START_TIME=$(date +%s%N)
    
    if timeout 600 mvn clean test -Dtest-order.enabled=true 2>&1 | tee "${TEST_RESULTS_DIR}/maven-60-500.log"; then
        success "Maven 60/500 test PASSED"
        echo "Test case: maven-60-500, status: PASS" >> "${TEST_RESULTS_DIR}/results.txt"
    else
        error "Maven 60/500 test FAILED or timed out"
        echo "Test case: maven-60-500, status: FAIL/TIMEOUT" >> "${TEST_RESULTS_DIR}/results.txt"
    fi
    
    local END_TIME=$(date +%s%N)
    local DURATION=$((($END_TIME - $START_TIME) / 1000000000))
    log "Duration: ${DURATION}s"
}

# Test 4: Extreme scale - 1000 test classes
test_extreme_scale_1000() {
    log "Testing extreme scale (1000 test classes)..."
    cd "${REPO_DIR}/phase5-scale-tests/extreme-scale-1000classes"
    
    if [ ! -f pom.xml ]; then
        error "Extreme scale test project not found or not initialized"
        return 1
    fi
    
    local START_TIME=$(date +%s%N)
    
    if timeout 900 mvn clean test -Dtest-order.enabled=true 2>&1 | tee "${TEST_RESULTS_DIR}/extreme-1000.log"; then
        success "Extreme scale 1000 classes test PASSED"
        echo "Test case: extreme-1000, status: PASS" >> "${TEST_RESULTS_DIR}/results.txt"
    else
        error "Extreme scale 1000 classes test FAILED or timed out"
        echo "Test case: extreme-1000, status: FAIL/TIMEOUT" >> "${TEST_RESULTS_DIR}/results.txt"
    fi
    
    local END_TIME=$(date +%s%N)
    local DURATION=$((($END_TIME - $START_TIME) / 1000000000))
    log "Duration: ${DURATION}s"
}

# Test 5: Extreme scale - 2000 test classes
test_extreme_scale_2000() {
    log "Testing extreme scale (2000 test classes)..."
    cd "${REPO_DIR}/phase5-scale-tests/extreme-scale-2000classes"
    
    if [ ! -f pom.xml ]; then
        error "Extreme scale 2000 test project not found or not initialized"
        return 1
    fi
    
    local START_TIME=$(date +%s%N)
    
    if timeout 1200 mvn clean test -Dtest-order.enabled=true 2>&1 | tee "${TEST_RESULTS_DIR}/extreme-2000.log"; then
        success "Extreme scale 2000 classes test PASSED"
        echo "Test case: extreme-2000, status: PASS" >> "${TEST_RESULTS_DIR}/results.txt"
    else
        error "Extreme scale 2000 classes test FAILED or timed out"
        echo "Test case: extreme-2000, status: FAIL/TIMEOUT" >> "${TEST_RESULTS_DIR}/results.txt"
    fi
    
    local END_TIME=$(date +%s%N)
    local DURATION=$((($END_TIME - $START_TIME) / 1000000000))
    log "Duration: ${DURATION}s"
}

# Run all tests
log "Starting Phase 5 Scalability Testing..."
log "Results will be written to: $TEST_RESULTS_DIR"

echo "=== Phase 5 Scalability Test Results ===" > "${TEST_RESULTS_DIR}/results.txt"
echo "Started: $(date)" >> "${TEST_RESULTS_DIR}/results.txt"
echo "" >> "${TEST_RESULTS_DIR}/results.txt"

test_maven_20_200
test_maven_40_300
test_maven_60_500
test_extreme_scale_1000 || true
test_extreme_scale_2000 || true

echo "" >> "${TEST_RESULTS_DIR}/results.txt"
echo "Completed: $(date)" >> "${TEST_RESULTS_DIR}/results.txt"

log "All tests completed!"
log "Summary saved to: ${TEST_RESULTS_DIR}/results.txt"
cat "${TEST_RESULTS_DIR}/results.txt"
