#!/bin/bash
# PHASE 3: Filesystem Edge Case Testing - Permission Issues
# Test read-only, no-execute, permission changes during execution

BASEDIR="/tmp/phase3-permission-tests"
TEST_LOG="$BASEDIR/test-results.log"
RESULTS=""

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
    > "$TEST_LOG"
    echo "=== PHASE 3: Permission Issues Testing ===" | tee "$TEST_LOG"
}

log_test() {
    local test_name="$1"
    local result="$2"
    echo "$test_name: $result" | tee -a "$TEST_LOG"
    RESULTS="${RESULTS}${test_name}: ${result}\n"
}

# Test PM1: Read-only test source directory
test_readonly_src_directory() {
    local readonly_src="$BASEDIR/readonly_src"
    mkdir -p "$readonly_src/src/test/java"
    touch "$readonly_src/src/test/java/TestClass.java"
    chmod 444 "$readonly_src" # Read-only directory
    
    # Try to list files
    if ls "$readonly_src" >/dev/null 2>&1; then
        log_test "PM1: Read-only source directory" "PASS - Can read"
    else
        log_test "PM1: Read-only source directory" "FAIL - Cannot read"
    fi
    chmod 755 "$readonly_src"  # Restore permissions for cleanup
}

# Test PM2: No execute permission on directories
test_no_execute_permission() {
    local no_exec_dir="$BASEDIR/no_exec"
    mkdir -p "$no_exec_dir/subdir"
    touch "$no_exec_dir/subdir/file.txt"
    chmod 600 "$no_exec_dir"  # No execute permission
    
    # Try to access subdirectory
    if [ -f "$no_exec_dir/subdir/file.txt" ] 2>/dev/null; then
        log_test "PM2: No execute permission on directories" "FAIL - Can access despite no-exec"
    else
        log_test "PM2: No execute permission on directories" "PASS - Cannot access"
    fi
    chmod 755 "$no_exec_dir"  # Restore
}

# Test PM3: Read-only .test-order cache directory
test_readonly_cache_dir() {
    local cache_dir="$BASEDIR/.test-order"
    mkdir -p "$cache_dir"
    echo '{}' > "$cache_dir/state.json"
    chmod 444 "$cache_dir"  # Read-only
    
    # Try to write to cache
    if echo '{}' > "$cache_dir/newfile.json" 2>/dev/null; then
        log_test "PM3: Read-only cache directory" "FAIL - Can write to read-only dir"
    else
        log_test "PM3: Read-only cache directory" "PASS - Cannot write to read-only dir"
    fi
    chmod 755 "$cache_dir"  # Restore
}

# Test PM4: No write permission for .test-order/
test_no_write_cache() {
    local cache_dir="$BASEDIR/.test-order-nw"
    mkdir -p "$cache_dir"
    chmod 555 "$cache_dir"  # Read and execute only
    
    # Try to write
    if touch "$cache_dir/file.txt" 2>/dev/null; then
        log_test "PM4: No write permission for .test-order/" "FAIL - Can write"
    else
        log_test "PM4: No write permission for .test-order/" "PASS - Cannot write"
    fi
    chmod 755 "$cache_dir"  # Restore
}

# Test PM5: No read permission for compiled classes
test_no_read_compiled() {
    local class_dir="$BASEDIR/target/classes"
    mkdir -p "$class_dir"
    touch "$class_dir/TestClass.class"
    chmod 000 "$class_dir/TestClass.class"  # No permissions
    
    # Try to read
    if [ -r "$class_dir/TestClass.class" ]; then
        log_test "PM5: No read permission for compiled classes" "FAIL - Can read"
    else
        log_test "PM5: No read permission for compiled classes" "PASS - Cannot read"
    fi
    chmod 644 "$class_dir/TestClass.class"  # Restore
}

# Test PM6: Changing permissions during execution
test_permission_change_during_exec() {
    local changing_file="$BASEDIR/changing_perm.txt"
    touch "$changing_file"
    chmod 644 "$changing_file"
    
    # Simulate permission change during reading
    (
        # Read in background with slight delay
        sleep 0.5
        chmod 000 "$changing_file"
    ) &
    
    # Try to read
    if timeout 2 cat "$changing_file" >/dev/null 2>&1; then
        log_test "PM6: Permission change during execution" "PARTIAL - Race condition"
    else
        log_test "PM6: Permission change during execution" "PASS - Handled gracefully"
    fi
    chmod 644 "$changing_file" 2>/dev/null || true
}

setup
test_readonly_src_directory
test_no_execute_permission
test_readonly_cache_dir
test_no_write_cache
test_no_read_compiled
test_permission_change_during_exec

echo -e "\n=== PERMISSION ISSUES TEST SUMMARY ===" | tee -a "$TEST_LOG"
echo -e "$RESULTS" | tee -a "$TEST_LOG"

# Cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"
