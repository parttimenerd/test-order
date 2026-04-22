#!/bin/bash
# PHASE 3: Real Maven Build Tests with Filesystem Edge Cases

RESULTS="/tmp/phase3-maven-results.txt"
BASEDIR="/tmp/phase3-maven-tests"

{
    echo "=========================================="
    echo "PHASE 3: Maven Filesystem Edge Case Tests"
    echo "=========================================="
    echo "Date: $(date)"
    echo ""
} | tee "$RESULTS"

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
}

# Test M1: Run Maven with read-only .test-order cache
test_maven_readonly_cache() {
    echo "TEST M1: Maven with read-only cache directory" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Clean and prepare
    rm -rf .test-order 2>/dev/null || true
    mkdir -p .test-order
    echo '{}' > .test-order/state.json
    
    # Make cache read-only
    chmod 444 .test-order
    
    # Try to run Maven
    if timeout 30 mvn test-order:learn -DskipTests 2>&1 | grep -q "FAILURE\|ERROR"; then
        echo "[FAIL] M1: Maven failed with read-only cache" | tee -a "$RESULTS"
    else
        echo "[PARTIAL] M1: Maven handling of read-only cache" | tee -a "$RESULTS"
    fi
    
    # Restore permissions
    chmod 755 .test-order 2>/dev/null || true
    rm -rf .test-order
}

# Test M2: Maven with corrupted cache JSON
test_maven_corrupted_cache() {
    echo "TEST M2: Maven with corrupted cache JSON" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create corrupted cache
    rm -rf .test-order 2>/dev/null || true
    mkdir -p .test-order
    echo 'not valid json {]' > .test-order/state.json
    
    # Try to run Maven
    if timeout 30 mvn test-order:learn -DskipTests 2>&1 | tee -a "$RESULTS" | grep -q "FAILURE\|ERROR\|exception"; then
        echo "[PASS] M2: Maven detected corrupted cache" | tee -a "$RESULTS"
    else
        echo "[FAIL] M2: Maven didn't detect corrupted cache" | tee -a "$RESULTS"
    fi
    
    rm -rf .test-order
}

# Test M3: Maven with cache in symlink
test_maven_symlink_cache() {
    echo "TEST M3: Maven with cache in symlinked directory" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create symlinked cache
    real_cache="/tmp/phase3-maven-symlink-cache"
    mkdir -p "$real_cache"
    ln -s "$real_cache" .test-order
    
    # Try to run Maven
    if timeout 30 mvn test-order:learn -DskipTests >/dev/null 2>&1; then
        echo "[PASS] M3: Maven works with symlinked cache" | tee -a "$RESULTS"
    else
        echo "[FAIL] M3: Maven failed with symlinked cache" | tee -a "$RESULTS"
    fi
    
    # Cleanup
    rm -rf .test-order "$real_cache"
}

# Test M4: Maven with .test-order as file not directory
test_maven_cache_is_file() {
    echo "TEST M4: Maven with cache as file not directory" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create cache as file
    touch .test-order
    
    # Try to run Maven
    if timeout 30 mvn test-order:learn -DskipTests 2>&1 | grep -q "FAILURE\|ERROR"; then
        echo "[PASS] M4: Maven detected cache as file" | tee -a "$RESULTS"
    else
        echo "[FAIL] M4: Maven didn't handle cache-is-file" | tee -a "$RESULTS"
    fi
    
    rm .test-order
}

# Test M5: Maven with very long cache path
test_maven_long_cache_path() {
    echo "TEST M5: Maven with very long cache path" | tee -a "$RESULTS"
    
    local long_path="$BASEDIR/$(printf 'a%.0s' {1..200})"
    mkdir -p "$long_path"
    cd "$long_path"
    
    # Copy example project
    cp -r /Users/i560383_1/code/experiments/test-order/test-order-example-junit5/* . 2>/dev/null || true
    
    # Try to run Maven with long paths
    if timeout 30 mvn test-order:learn -DskipTests >/dev/null 2>&1; then
        echo "[PASS] M5: Maven works with long paths" | tee -a "$RESULTS"
    else
        echo "[FAIL] M5: Maven failed with long paths" | tee -a "$RESULTS"
    fi
}

# Test M6: Maven with multiple concurrent builds (race condition)
test_maven_concurrent_builds() {
    echo "TEST M6: Concurrent Maven builds" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Run two Maven processes concurrently
    timeout 60 mvn clean test-order:learn -DskipTests >/dev/null 2>&1 &
    pid1=$!
    
    timeout 60 mvn clean test-order:learn -DskipTests >/dev/null 2>&1 &
    pid2=$!
    
    wait $pid1
    result1=$?
    
    wait $pid2
    result2=$?
    
    if [ $result1 -eq 0 ] && [ $result2 -eq 0 ]; then
        echo "[PASS] M6: Concurrent builds handled correctly" | tee -a "$RESULTS"
    else
        echo "[FAIL] M6: Concurrent builds failed (race condition)" | tee -a "$RESULTS"
    fi
    
    mvn clean -q 2>/dev/null || true
}

# Test M7: Maven with insufficient disk space (simulate)
test_maven_cache_write_failure() {
    echo "TEST M7: Maven cache write failure handling" | tee -a "$RESULTS"
    
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create read-only parent directory
    mkdir -p test_cache_parent
    chmod 555 test_cache_parent
    
    # Try to create cache in read-only parent
    timeout 30 mvn test-order:learn -DskipTests -Dtest-order.cache=./test_cache_parent/.test-order 2>&1 | tee -a "$RESULTS" >/dev/null
    
    # Check if error was handled gracefully
    chmod 755 test_cache_parent
    rm -rf test_cache_parent
    
    echo "[CHECK] M7: Verify error messages in log" | tee -a "$RESULTS"
}

setup
test_maven_readonly_cache
test_maven_corrupted_cache
test_maven_symlink_cache
test_maven_cache_is_file
test_maven_long_cache_path
test_maven_concurrent_builds
test_maven_cache_write_failure

echo "" | tee -a "$RESULTS"
echo "Maven filesystem tests complete" | tee -a "$RESULTS"

# Cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"

echo "" | tee -a "$RESULTS"
echo "RESULTS FILE: $RESULTS"
cat "$RESULTS"
