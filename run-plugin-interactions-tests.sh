#!/bin/bash

# Plugin Interactions Test Runner
# Tests test-order with various Maven and Gradle plugins

set -e

PROJECT_DIR="/Users/i560383_1/code/experiments/test-order"
PLUGIN_INTERACTIONS_DIR="$PROJECT_DIR/phase5-plugin-interactions"
RESULTS_FILE="$PROJECT_DIR/PHASE-5-PLUGIN-INTERACTIONS-REPORT.md"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_header() {
    echo -e "\n${BLUE}========== $1 ==========${NC}\n"
}

log_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

log_error() {
    echo -e "${RED}✗ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Test results storage
declare -a PASSED_TESTS
declare -a FAILED_TESTS

# Initialize report
echo "# Phase 5: Plugin Interactions and Conflicts Report" > "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"
echo "**Generated:** $(date)" >> "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"

log_header "PHASE 5: PLUGIN INTERACTIONS TESTING"
echo "Testing interaction patterns between test-order and other Maven/Gradle plugins"

# Test 1: JaCoCo Integration
log_header "Test 1: test-order + JaCoCo Code Coverage"
cd "$PLUGIN_INTERACTIONS_DIR/jacoco-test"

if mvn clean test jacoco:report 2>&1 | tee jacoco-test.log; then
    log_success "JaCoCo integration test passed"
    PASSED_TESTS+=("JaCoCo Integration (Maven)")
    if [ -f target/site/jacoco/index.html ]; then
        log_success "JaCoCo report generated successfully"
    else
        log_warning "JaCoCo report not found at expected location"
    fi
else
    log_error "JaCoCo integration test failed"
    FAILED_TESTS+=("JaCoCo Integration (Maven)")
    tail -50 jacoco-test.log >> "$RESULTS_FILE"
fi

# Test 2: PIT Mutation Testing
log_header "Test 2: test-order + PIT Mutation Testing"
cd "$PLUGIN_INTERACTIONS_DIR/pit-test"

if mvn clean test 2>&1 | tee pit-test.log; then
    log_success "PIT mutation testing test passed"
    PASSED_TESTS+=("PIT Mutation Testing (Maven)")
    
    # Try to run mutations (this may not work without full build)
    if mvn org.pitest:pitest-maven:mutationCoverage 2>&1 | tee pit-mutations.log; then
        log_success "PIT mutations ran successfully"
    else
        log_warning "PIT mutations execution had issues"
    fi
else
    log_error "PIT integration test failed"
    FAILED_TESTS+=("PIT Mutation Testing (Maven)")
fi

# Test 3: Maven Shade Plugin
log_header "Test 3: test-order + Maven Shade Plugin"
cd "$PLUGIN_INTERACTIONS_DIR/shade-test"

if mvn clean package 2>&1 | tee shade-test.log; then
    log_success "Maven Shade plugin integration test passed"
    PASSED_TESTS+=("Maven Shade Plugin (Maven)")
    
    if [ -f target/test-order-shade-test-1.0.0-shaded.jar ]; then
        log_success "Shaded JAR created successfully"
    else
        log_warning "Expected shaded JAR not found"
    fi
else
    log_error "Maven Shade plugin integration test failed"
    FAILED_TESTS+=("Maven Shade Plugin (Maven)")
fi

# Test 4: Maven Enforcer Plugin
log_header "Test 4: test-order + Maven Enforcer Plugin"
cd "$PLUGIN_INTERACTIONS_DIR/enforcer-test"

if mvn clean test 2>&1 | tee enforcer-test.log; then
    log_success "Maven Enforcer plugin integration test passed"
    PASSED_TESTS+=("Maven Enforcer Plugin (Maven)")
else
    log_error "Maven Enforcer plugin integration test failed"
    FAILED_TESTS+=("Maven Enforcer Plugin (Maven)")
fi

# Test 5: Gradle Build Cache
log_header "Test 5: test-order with Gradle Build Cache"
cd "$PLUGIN_INTERACTIONS_DIR/gradle-cache-test"

# First run (builds cache)
if gradle --build-cache test 2>&1 | tee gradle-cache-first.log; then
    log_success "First Gradle run with cache passed"
    
    # Second run (uses cache)
    if gradle --build-cache test 2>&1 | tee gradle-cache-second.log; then
        log_success "Second Gradle run with cache passed"
        
        # Check for cache hits
        if grep -q "FROM-CACHE" gradle-cache-second.log 2>/dev/null; then
            log_success "Build cache was utilized"
            PASSED_TESTS+=("Gradle Build Cache")
        else
            log_warning "Build cache may not have been used"
            PASSED_TESTS+=("Gradle Build Cache (partial)")
        fi
    else
        log_error "Second Gradle run failed"
        FAILED_TESTS+=("Gradle Build Cache")
    fi
else
    log_error "First Gradle run failed"
    FAILED_TESTS+=("Gradle Build Cache")
fi

# Test 6: Gradle Parallel Execution
log_header "Test 6: test-order with Gradle Parallel Execution"
cd "$PLUGIN_INTERACTIONS_DIR/parallel-test"

if gradle --parallel test 2>&1 | tee gradle-parallel.log; then
    log_success "Gradle parallel execution test passed"
    PASSED_TESTS+=("Gradle Parallel Execution")
else
    log_error "Gradle parallel execution test failed"
    FAILED_TESTS+=("Gradle Parallel Execution")
fi

# Generate final report
log_header "GENERATING FINAL REPORT"

{
    echo "## Test Results Summary"
    echo ""
    echo "### Passed Tests (${#PASSED_TESTS[@]})"
    for test in "${PASSED_TESTS[@]}"; do
        echo "- ✓ $test"
    done
    echo ""
    echo "### Failed Tests (${#FAILED_TESTS[@]})"
    for test in "${FAILED_TESTS[@]}"; do
        echo "- ✗ $test"
    done
    echo ""
    echo "## Detailed Test Logs"
    echo ""
} >> "$RESULTS_FILE"

# Copy relevant logs
if [ -f "$PLUGIN_INTERACTIONS_DIR/jacoco-test/jacoco-test.log" ]; then
    {
        echo "### JaCoCo Test Log"
        echo "\`\`\`"
        tail -100 "$PLUGIN_INTERACTIONS_DIR/jacoco-test/jacoco-test.log"
        echo "\`\`\`"
        echo ""
    } >> "$RESULTS_FILE"
fi

# Summary
echo ""
log_header "SUMMARY"
echo -e "Passed: ${GREEN}${#PASSED_TESTS[@]}${NC}"
echo -e "Failed: ${RED}${#FAILED_TESTS[@]}${NC}"
echo ""
echo "Report saved to: $RESULTS_FILE"
