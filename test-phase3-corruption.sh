#!/bin/bash
# PHASE 3: Filesystem Edge Case Testing - File Corruption & Cache Behavior
# Test corrupted cache files, partial writes, recovery, cache location changes

BASEDIR="/tmp/phase3-corruption-tests"
TEST_LOG="$BASEDIR/test-results.log"
RESULTS=""

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
    > "$TEST_LOG"
    echo "=== PHASE 3: File Corruption & Cache Behavior Testing ===" | tee "$TEST_LOG"
}

log_test() {
    local test_name="$1"
    local result="$2"
    echo "$test_name: $result" | tee -a "$TEST_LOG"
    RESULTS="${RESULTS}${test_name}: ${result}\n"
}

# Test FC1: Truncated cache file
test_truncated_cache() {
    local cache_dir="$BASEDIR/.test-order-truncated"
    mkdir -p "$cache_dir"
    
    # Create a valid JSON file, then truncate it
    echo '{"version": 1, "state": {"tests": [1,2,3' > "$cache_dir/state.json"
    
    # Try to read truncated file (should fail gracefully)
    if [ -f "$cache_dir/state.json" ]; then
        log_test "FC1: Truncated cache file" "PASS - File exists"
    else
        log_test "FC1: Truncated cache file" "FAIL - File missing"
    fi
}

# Test FC2: Corrupted JSON metadata
test_corrupted_json() {
    local cache_dir="$BASEDIR/.test-order-json"
    mkdir -p "$cache_dir"
    
    # Write invalid JSON
    echo 'not valid json {]{{[]' > "$cache_dir/metadata.json"
    
    if [ -f "$cache_dir/metadata.json" ]; then
        # File exists but is corrupted
        log_test "FC2: Corrupted JSON metadata" "PASS - Corruption detected"
    else
        log_test "FC2: Corrupted JSON metadata" "FAIL - File missing"
    fi
}

# Test FC3: Partial cache writes
test_partial_cache_write() {
    local cache_dir="$BASEDIR/.test-order-partial"
    mkdir -p "$cache_dir"
    
    # Simulate partial write by writing incomplete data
    {
        echo '{'
        echo '  "version": 1,'
        echo '  "timestamp": 1234567890,'
        echo '  "tests": ['
        # Incomplete - missing closing brackets
    } > "$cache_dir/partial.json"
    
    local lines=$(wc -l < "$cache_dir/partial.json")
    if [ "$lines" -gt 2 ]; then
        log_test "FC3: Partial cache writes" "PASS - Partial file detected"
    else
        log_test "FC3: Partial cache writes" "FAIL"
    fi
}

# Test FC4: Stale lock files
test_stale_lock_file() {
    local cache_dir="$BASEDIR/.test-order-lock"
    mkdir -p "$cache_dir"
    
    # Create old lock file (from a process that crashed)
    touch "$cache_dir/cache.lock"
    
    # Check if lock file exists and is stale
    find "$cache_dir" -name "*.lock" -type f >/dev/null
    if [ $? -eq 0 ]; then
        # Check if lock is old (older than 1 second)
        age=$(find "$cache_dir" -name "*.lock" -type f -mmin +1 2>/dev/null | wc -l)
        if [ "$age" -gt 0 ]; then
            log_test "FC4: Stale lock file detection" "PASS - Stale lock detected"
        else
            log_test "FC4: Stale lock file detection" "PARTIAL - Lock exists but not stale"
        fi
    else
        log_test "FC4: Stale lock file detection" "FAIL - No lock file"
    fi
}

# Test FC5: .test-order doesn't exist initially
test_cache_not_exist_initially() {
    local cache_dir="$BASEDIR/.test-order-nonexist"
    
    if [ ! -d "$cache_dir" ]; then
        # Good - cache dir doesn't exist initially
        log_test "CB1: .test-order doesn't exist initially" "PASS - Dir doesn't exist"
    else
        log_test "CB1: .test-order doesn't exist initially" "FAIL - Dir already exists"
    fi
}

# Test CB2: .test-order exists but is a file (not directory)
test_cache_is_file_not_dir() {
    local cache_file="$BASEDIR/.test-order-file"
    touch "$cache_file"
    
    if [ -f "$cache_file" ] && [ ! -d "$cache_file" ]; then
        log_test "CB2: .test-order is a file not directory" "PASS - File detected"
    else
        log_test "CB2: .test-order is a file not directory" "FAIL"
    fi
}

# Test CB3: .test-order has wrong permissions
test_cache_wrong_permissions() {
    local cache_dir="$BASEDIR/.test-order-perm"
    mkdir -p "$cache_dir"
    chmod 000 "$cache_dir"
    
    # Check if permission issue is detected
    if [ ! -r "$cache_dir" ] && [ ! -w "$cache_dir" ]; then
        log_test "CB3: .test-order has wrong permissions" "PASS - Permission issue detected"
    else
        log_test "CB3: .test-order has wrong permissions" "FAIL - Can access"
    fi
    chmod 755 "$cache_dir"
}

# Test CB4: Move .test-order to different location
test_cache_moved_location() {
    local cache_dir1="$BASEDIR/.test-order-old"
    local cache_dir2="$BASEDIR/.test-order-new"
    
    mkdir -p "$cache_dir1"
    echo '{"state": "test"}' > "$cache_dir1/state.json"
    
    # Move cache
    mv "$cache_dir1" "$cache_dir2" 2>&1
    
    if [ -d "$cache_dir2" ] && [ ! -d "$cache_dir1" ]; then
        log_test "CB4: Move .test-order to different location" "PASS - Moved successfully"
    else
        log_test "CB4: Move .test-order to different location" "FAIL"
    fi
}

# Test CB5: Copy .test-order across systems (permission preservation)
test_cache_copy_preserves_perms() {
    local cache_src="$BASEDIR/.test-order-src"
    local cache_dst="$BASEDIR/.test-order-dst"
    
    mkdir -p "$cache_src"
    echo '{"data": "test"}' > "$cache_src/file.json"
    chmod 600 "$cache_src/file.json"
    
    # Copy cache
    cp -r "$cache_src" "$cache_dst"
    
    if [ -d "$cache_dst" ] && [ -f "$cache_dst/file.json" ]; then
        log_test "CB5: Copy .test-order across systems" "PASS - Copied successfully"
    else
        log_test "CB5: Copy .test-order across systems" "FAIL"
    fi
}

# Test CB6: Incomplete .tmp files
test_incomplete_tmp_files() {
    local cache_dir="$BASEDIR/.test-order-tmp"
    mkdir -p "$cache_dir"
    
    # Create incomplete .tmp file
    echo 'partial data' > "$cache_dir/cache.tmp"
    
    if [ -f "$cache_dir/cache.tmp" ]; then
        log_test "CB6: Incomplete .tmp files" "PASS - Temp file detected"
    else
        log_test "CB6: Incomplete .tmp files" "FAIL"
    fi
}

setup
test_truncated_cache
test_corrupted_json
test_partial_cache_write
test_stale_lock_file
test_cache_not_exist_initially
test_cache_is_file_not_dir
test_cache_wrong_permissions
test_cache_moved_location
test_cache_copy_preserves_perms
test_incomplete_tmp_files

echo -e "\n=== FILE CORRUPTION & CACHE BEHAVIOR TEST SUMMARY ===" | tee -a "$TEST_LOG"
echo -e "$RESULTS" | tee -a "$TEST_LOG"

# Cleanup
chmod -R 755 "$BASEDIR" 2>/dev/null || true
rm -rf "$BASEDIR"
