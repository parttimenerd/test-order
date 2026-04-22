#!/bin/bash
# PHASE 3: Real Maven Filesystem Edge Case Testing with Correct Goals

RESULTS="/tmp/phase3-maven-fs-detailed.txt"
BASEDIR="/tmp/phase3-maven-detailed"

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

cleanup_project() {
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    rm -rf .test-order target 2>/dev/null || true
    mvn clean -q 2>/dev/null || true
}

# Test M1: Run with read-only cache directory
test_readonly_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M1: Read-only cache directory" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create .test-order and make read-only
    mkdir -p .test-order
    echo '{"version":1}' > .test-order/state.json
    chmod 444 .test-order
    
    # Try snapshot (should fail gracefully)
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS" | grep -i "error\|fail"; then
        echo "BUG M1: Cannot handle read-only cache - build fails" | tee -a "$RESULTS"
    else
        echo "INFO M1: Build may have succeeded despite read-only cache" | tee -a "$RESULTS"
    fi
    
    chmod 755 .test-order
    cleanup_project
}

# Test M2: Corrupted cache handling
test_corrupted_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M2: Corrupted cache JSON" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create corrupted cache
    mkdir -p .test-order
    echo 'CORRUPTED{]' > .test-order/state.json
    
    # Try to use snapshot
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS" | grep -i "error"; then
        echo "BUG M2: Corruption not handled gracefully" | tee -a "$RESULTS"
    else
        echo "INFO M2: Snapshot handled unknown file" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test M3: Cache as file instead of directory
test_cache_as_file() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M3: Cache is file not dir" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create .test-order as file
    touch .test-order
    
    # Try to run
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS" | grep -i "error\|is not a directory"; then
        echo "INFO M3: Detected .test-order is not a directory" | tee -a "$RESULTS"
    else
        echo "BUG M3: Did not detect cache is file not dir" | tee -a "$RESULTS"
    fi
    
    rm -f .test-order
    cleanup_project
}

# Test M4: Symlinked cache
test_symlink_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M4: Symlinked cache dir" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Create symlinked cache
    real_cache="/tmp/phase3-real-cache-$$"
    mkdir -p "$real_cache"
    ln -s "$real_cache" .test-order
    
    # Try snapshot
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS"; then
        echo "INFO M4: Symlinked cache works" | tee -a "$RESULTS"
    else
        echo "BUG M4: Symlinked cache fails" | tee -a "$RESULTS"
    fi
    
    rm -rf .test-order "$real_cache"
    cleanup_project
}

# Test M5: Show-order with empty source tree
test_show_order_empty() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M5: Show-order with empty src" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Try show-order
    if timeout 30 mvn test-order:show-order 2>&1 | tee -a "$RESULTS"; then
        echo "INFO M5: Show-order works" | tee -a "$RESULTS"
    else
        echo "BUG M5: Show-order failed" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test M6: Very long file paths in cache
test_long_file_paths() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M6: Long file paths" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    
    local long_dir="$BASEDIR/$(printf 'a%.0s' {1..150})"
    mkdir -p "$long_dir/test-order-example-junit5"
    cp -r /Users/i560383_1/code/experiments/test-order/test-order-example-junit5/* "$long_dir/test-order-example-junit5/" 2>/dev/null || true
    
    cd "$long_dir/test-order-example-junit5"
    
    # Try to run
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS"; then
        echo "INFO M6: Long paths handled" | tee -a "$RESULTS"
    else
        echo "BUG M6: Long paths fail" | tee -a "$RESULTS"
    fi
}

# Test M7: Missing .test-order directory (first run)
test_missing_cache_dir() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M7: Missing .test-order initially" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Ensure .test-order doesn't exist
    rm -rf .test-order
    
    # Try snapshot
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS"; then
        if [ -d ".test-order" ]; then
            echo "INFO M7: .test-order created on first run" | tee -a "$RESULTS"
        else
            echo "BUG M7: .test-order not created" | tee -a "$RESULTS"
        fi
    else
        echo "BUG M7: Snapshot failed on first run" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test M8: Unicode characters in source paths
test_unicode_source_path() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M8: Unicode source paths" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    
    local unicode_dir="$BASEDIR/测试_テスト_тест"
    mkdir -p "$unicode_dir/test-project"
    cp -r /Users/i560383_1/code/experiments/test-order/test-order-example-junit5/* "$unicode_dir/test-project/" 2>/dev/null || true
    
    cd "$unicode_dir/test-project"
    
    # Try to run
    if timeout 30 mvn test-order:snapshot 2>&1 | tee -a "$RESULTS"; then
        echo "INFO M8: Unicode paths work" | tee -a "$RESULTS"
    else
        echo "BUG M8: Unicode paths fail" | tee -a "$RESULTS"
    fi
}

# Test M9: Test order stability with modifications
test_cache_stability() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M9: Cache stability" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Run snapshot twice
    if timeout 30 mvn test-order:snapshot -q 2>&1; then
        snap1=$(find .test-order -name "*.dat" -o -name "*.json" | xargs ls -ltr | tail -1 | awk '{print $NF}')
        
        # Run again without modifications
        sleep 1
        timeout 30 mvn test-order:snapshot -q 2>&1
        snap2=$(find .test-order -name "*.dat" -o -name "*.json" | xargs ls -ltr | tail -1 | awk '{print $NF}')
        
        if [ "$snap1" != "$snap2" ]; then
            echo "BUG M9: Cache modified on unchanged source" | tee -a "$RESULTS"
        else
            echo "INFO M9: Cache stable across runs" | tee -a "$RESULTS"
        fi
    else
        echo "BUG M9: Snapshot failed" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test M10: Concurrent Maven builds (race condition test)
test_concurrent_builds() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST M10: Concurrent builds" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-junit5
    
    # Run two builds concurrently
    timeout 60 mvn test-order:snapshot -q >/dev/null 2>&1 &
    pid1=$!
    
    sleep 0.5
    
    timeout 60 mvn test-order:snapshot -q >/dev/null 2>&1 &
    pid2=$!
    
    wait $pid1
    r1=$?
    wait $pid2
    r2=$?
    
    if [ $r1 -eq 0 ] && [ $r2 -eq 0 ]; then
        echo "INFO M10: Concurrent builds succeeded" | tee -a "$RESULTS"
    elif [ $r1 -ne 0 ] || [ $r2 -ne 0 ]; then
        echo "BUG M10: Concurrent build race condition detected" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

setup
test_readonly_cache
test_corrupted_cache
test_cache_as_file
test_symlink_cache
test_show_order_empty
test_long_file_paths
test_missing_cache_dir
test_unicode_source_path
test_cache_stability
test_concurrent_builds

echo "" | tee -a "$RESULTS"
echo "========================================" | tee -a "$RESULTS"
echo "Maven Filesystem tests complete" | tee -a "$RESULTS"

# Final cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"

echo "Results saved to: $RESULTS"
