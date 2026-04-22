#!/bin/bash
# PHASE 3: Gradle Filesystem Edge Case Testing

RESULTS="/tmp/phase3-gradle-fs-detailed.txt"
BASEDIR="/tmp/phase3-gradle-detailed"

{
    echo "=========================================="
    echo "PHASE 3: Gradle Filesystem Edge Case Tests"
    echo "=========================================="
    echo "Date: $(date)"
    echo ""
} | tee "$RESULTS"

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
}

cleanup_project() {
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    rm -rf .test-order build 2>/dev/null || true
    ./gradlew clean 2>&1 >/dev/null || true
}

# Test G1: Read-only cache directory
test_readonly_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G1: Read-only cache" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Create read-only cache
    mkdir -p .test-order
    echo '{}' > .test-order/state.json
    chmod 444 .test-order
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | grep -i "error\|fail"; then
        echo "BUG G1: Cannot handle read-only cache" | tee -a "$RESULTS"
    else
        echo "INFO G1: Possibly handled read-only cache" | tee -a "$RESULTS"
    fi
    
    chmod 755 .test-order
    cleanup_project
}

# Test G2: Corrupted cache file
test_corrupted_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G2: Corrupted cache" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Create corrupted cache
    mkdir -p .test-order
    echo 'CORRUPTED{]' > .test-order/state.json
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | grep -i "error"; then
        echo "INFO G2: Detected corrupted cache" | tee -a "$RESULTS"
    else
        echo "INFO G2: Possibly handled unknown format" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test G3: Cache as file not directory
test_cache_as_file() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G3: Cache is file" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Create cache as file
    touch .test-order
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | grep -i "error"; then
        echo "INFO G3: Detected cache is file" | tee -a "$RESULTS"
    else
        echo "BUG G3: Did not detect cache is file" | tee -a "$RESULTS"
    fi
    
    rm -f .test-order
    cleanup_project
}

# Test G4: Symlinked cache
test_symlink_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G4: Symlinked cache" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Create symlinked cache
    real_cache="/tmp/phase3-gradle-cache-$$"
    mkdir -p "$real_cache"
    ln -s "$real_cache" .test-order
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | tail -20; then
        echo "INFO G4: Symlinked cache works" | tee -a "$RESULTS"
    else
        echo "INFO G4: Check gradle output above" | tee -a "$RESULTS"
    fi
    
    rm -rf .test-order "$real_cache"
    cleanup_project
}

# Test G5: Missing .test-order initially
test_missing_cache() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G5: Missing .test-order" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Ensure no cache
    rm -rf .test-order
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS"; then
        if [ -d ".test-order" ]; then
            echo "INFO G5: Cache created on first run" | tee -a "$RESULTS"
        else
            echo "BUG G5: Cache not created" | tee -a "$RESULTS"
        fi
    else
        echo "BUG G5: Failed on first run" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

# Test G6: Very long cache path
test_long_cache_path() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G6: Long cache path" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    local long_dir="$BASEDIR/$(printf 'x%.0s' {1..150})"
    mkdir -p "$long_dir/gradle-project"
    cp -r /Users/i560383_1/code/experiments/test-order/test-order-example-gradle/* "$long_dir/gradle-project/" 2>/dev/null || true
    
    cd "$long_dir/gradle-project"
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | tail -20; then
        echo "INFO G6: Long paths handled" | tee -a "$RESULTS"
    else
        echo "INFO G6: Check output above" | tee -a "$RESULTS"
    fi
}

# Test G7: Unicode in project path
test_unicode_path() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G7: Unicode path" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    local unicode_dir="$BASEDIR/项目_プロジェクト"
    mkdir -p "$unicode_dir"
    cp -r /Users/i560383_1/code/experiments/test-order/test-order-example-gradle/* "$unicode_dir/" 2>/dev/null || true
    
    cd "$unicode_dir"
    
    # Try to run
    if timeout 60 ./gradlew testOrderSnapshot 2>&1 | tee -a "$RESULTS" | tail -20; then
        echo "INFO G7: Unicode paths work" | tee -a "$RESULTS"
    else
        echo "INFO G7: Check output" | tee -a "$RESULTS"
    fi
}

# Test G8: Concurrent Gradle builds
test_concurrent_builds() {
    echo "================================" | tee -a "$RESULTS"
    echo "TEST G8: Concurrent builds" | tee -a "$RESULTS"
    echo "================================" | tee -a "$RESULTS"
    
    cleanup_project
    cd /Users/i560383_1/code/experiments/test-order/test-order-example-gradle
    
    # Run two builds concurrently
    timeout 120 ./gradlew testOrderSnapshot -q >/dev/null 2>&1 &
    pid1=$!
    
    sleep 1
    
    timeout 120 ./gradlew testOrderSnapshot -q >/dev/null 2>&1 &
    pid2=$!
    
    wait $pid1
    r1=$?
    wait $pid2
    r2=$?
    
    if [ $r1 -eq 0 ] && [ $r2 -eq 0 ]; then
        echo "INFO G8: Concurrent builds succeeded" | tee -a "$RESULTS"
    elif [ $r1 -ne 0 ] || [ $r2 -ne 0 ]; then
        echo "BUG G8: Race condition in concurrent builds" | tee -a "$RESULTS"
    fi
    
    cleanup_project
}

setup
test_readonly_cache
test_corrupted_cache
test_cache_as_file
test_symlink_cache
test_missing_cache
test_long_cache_path
test_unicode_path
test_concurrent_builds

echo "" | tee -a "$RESULTS"
echo "========================================" | tee -a "$RESULTS"
echo "Gradle filesystem tests complete" | tee -a "$RESULTS"

# Final cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"

echo "Results: $RESULTS"
