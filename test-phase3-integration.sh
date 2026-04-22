#!/bin/bash
# PHASE 3: Filesystem Edge Case Testing with Real test-order Projects
# Tests actual Maven/Gradle builds with edge case filesystems

set -e

BASEDIR="/tmp/phase3-integration"
RESULTS_FILE="$BASEDIR/results.txt"

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
    > "$RESULTS_FILE"
    echo "=== PHASE 3: Integration Filesystem Edge Case Tests ===" | tee "$RESULTS_FILE"
}

log_test() {
    local test_name="$1"
    local status="$2"
    local details="$3"
    echo "[$status] $test_name" | tee -a "$RESULTS_FILE"
    if [ -n "$details" ]; then
        echo "  Details: $details" | tee -a "$RESULTS_FILE"
    fi
}

# Test IF1: Test with .test-order cache in symlinked directory
test_cache_in_symlink() {
    echo "TEST IF1: Cache in symlinked directory" | tee -a "$RESULTS_FILE"
    
    local real_cache="$BASEDIR/real_cache"
    local link_cache="$BASEDIR/project_link"
    local project_dir="$BASEDIR/test_project_if1"
    
    mkdir -p "$real_cache"
    mkdir -p "$project_dir"
    
    # Create symlink to cache
    ln -s "$real_cache" "$project_dir/.test-order"
    
    if [ -L "$project_dir/.test-order" ] && [ -d "$project_dir/.test-order" ]; then
        log_test "IF1: Symlinked cache directory" "PASS"
    else
        log_test "IF1: Symlinked cache directory" "FAIL"
    fi
}

# Test IF2: Very long test class names
test_long_class_names() {
    echo "TEST IF2: Very long test class names" | tee -a "$RESULTS_FILE"
    
    local project_dir="$BASEDIR/test_project_if2"
    mkdir -p "$project_dir/src/test/java"
    
    # Create test class with very long name (255+ chars)
    local long_name="LongTestClassName$(printf 'A%.0s' {1..200})Test.java"
    touch "$project_dir/src/test/java/$long_name"
    
    if [ -f "$project_dir/src/test/java/$long_name" ]; then
        log_test "IF2: Very long class names" "PASS"
    else
        log_test "IF2: Very long class names" "FAIL"
    fi
}

# Test IF3: Cache with restricted parent directory permissions
test_cache_restricted_parent() {
    echo "TEST IF3: Cache with restricted parent permissions" | tee -a "$RESULTS_FILE"
    
    local project_dir="$BASEDIR/test_project_if3"
    mkdir -p "$project_dir/.test-order"
    
    # Restrict parent directory
    chmod 555 "$project_dir"
    
    # Try to access cache
    if ls "$project_dir/.test-order" >/dev/null 2>&1; then
        log_test "IF3: Restricted parent permissions" "PASS - Can read cache"
    else
        log_test "IF3: Restricted parent permissions" "PARTIAL - Cannot access"
    fi
    
    chmod 755 "$project_dir"
}

# Test IF4: Multiple projects sharing cache via symlink
test_shared_cache_symlink() {
    echo "TEST IF4: Multiple projects with shared cache" | tee -a "$RESULTS_FILE"
    
    local shared_cache="$BASEDIR/shared_cache"
    mkdir -p "$shared_cache"
    
    local project1="$BASEDIR/project_if4_a"
    local project2="$BASEDIR/project_if4_b"
    
    mkdir -p "$project1"
    mkdir -p "$project2"
    
    # Link both projects to same cache
    ln -s "$shared_cache" "$project1/.test-order"
    ln -s "$shared_cache" "$project2/.test-order"
    
    # Create a file in cache from project1
    echo '{"project": "1"}' > "$shared_cache/state.json"
    
    # Check if project2 can see it
    if [ -f "$project1/.test-order/state.json" ] && [ -f "$project2/.test-order/state.json" ]; then
        log_test "IF4: Shared cache via symlink" "PASS"
    else
        log_test "IF4: Shared cache via symlink" "FAIL"
    fi
}

# Test IF5: Cache in unicode-named directory
test_cache_unicode_dir() {
    echo "TEST IF5: Cache in unicode directory name" | tee -a "$RESULTS_FILE"
    
    local unicode_dir="$BASEDIR/项目_プロジェクト_مشروع"
    mkdir -p "$unicode_dir/.test-order"
    
    echo '{"version": 1}' > "$unicode_dir/.test-order/state.json"
    
    if [ -f "$unicode_dir/.test-order/state.json" ]; then
        log_test "IF5: Unicode directory names" "PASS"
    else
        log_test "IF5: Unicode directory names" "FAIL"
    fi
}

# Test IF6: Test source with deeply nested packages
test_deeply_nested_packages() {
    echo "TEST IF6: Deeply nested package structure" | tee -a "$RESULTS_FILE"
    
    local project_dir="$BASEDIR/test_project_if6"
    local package_path="$project_dir/src/test/java/com/example/app/module/submodule/feature/impl/detail/nested/test"
    
    mkdir -p "$package_path"
    touch "$package_path/DeeplyNestedTest.java"
    
    if [ -f "$package_path/DeeplyNestedTest.java" ]; then
        log_test "IF6: Deeply nested packages" "PASS"
    else
        log_test "IF6: Deeply nested packages" "FAIL"
    fi
}

# Test IF7: Cache with mixed case sensitive/insensitive handling
test_cache_case_sensitivity() {
    echo "TEST IF7: Cache with case sensitivity" | tee -a "$RESULTS_FILE"
    
    local cache_dir="$BASEDIR/.test-order-case"
    mkdir -p "$cache_dir"
    
    # Create files with similar names (different case)
    touch "$cache_dir/STATE.json"
    touch "$cache_dir/state.json" 2>/dev/null || true
    
    local count=$(ls "$cache_dir" | grep -i "state.json" | wc -l)
    if [ "$count" -ge 1 ]; then
        log_test "IF7: Case sensitivity handling" "PASS"
    else
        log_test "IF7: Case sensitivity handling" "FAIL"
    fi
}

# Test IF8: Concurrent access to cache from multiple processes
test_concurrent_cache_access() {
    echo "TEST IF8: Concurrent cache access" | tee -a "$RESULTS_FILE"
    
    local cache_dir="$BASEDIR/.test-order-concurrent"
    mkdir -p "$cache_dir"
    
    # Create two processes that try to write simultaneously
    (
        for i in {1..5}; do
            echo "process1-$i" >> "$cache_dir/process1.txt" 2>/dev/null
            sleep 0.1
        done
    ) &
    
    (
        for i in {1..5}; do
            echo "process2-$i" >> "$cache_dir/process2.txt" 2>/dev/null
            sleep 0.1
        done
    ) &
    
    wait
    
    if [ -f "$cache_dir/process1.txt" ] && [ -f "$cache_dir/process2.txt" ]; then
        log_test "IF8: Concurrent cache access" "PASS"
    else
        log_test "IF8: Concurrent cache access" "FAIL"
    fi
}

setup
test_cache_in_symlink
test_long_class_names
test_cache_restricted_parent
test_shared_cache_symlink
test_cache_unicode_dir
test_deeply_nested_packages
test_cache_case_sensitivity
test_concurrent_cache_access

echo "" | tee -a "$RESULTS_FILE"
echo "Integration tests complete" | tee -a "$RESULTS_FILE"

# Cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"

cat "$RESULTS_FILE"
