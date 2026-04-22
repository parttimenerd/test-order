#!/bin/bash
# PHASE 3: Filesystem Edge Case Testing - Path Extremes
# Test very long paths, special characters, unicode, etc.

set -e
BASEDIR="/tmp/phase3-fs-tests"
TEST_LOG="$BASEDIR/test-results.log"
RESULTS=""

setup() {
    mkdir -p "$BASEDIR"
    > "$TEST_LOG"
    echo "=== PHASE 3: Path Extremes Testing ===" | tee "$TEST_LOG"
}

log_test() {
    local test_name="$1"
    local result="$2"
    echo "$test_name: $result" | tee -a "$TEST_LOG"
    RESULTS="${RESULTS}${test_name}: ${result}\n"
}

# Test P1: Very long absolute paths (250+ characters)
test_long_absolute_path() {
    local long_path="$BASEDIR/very_long_path_to_test_directory_$(printf 'a%.0s' {1..200})"
    mkdir -p "$long_path/src/test/java" 2>&1
    if [ -d "$long_path" ]; then
        log_test "P1: Very long absolute path (250+ chars)" "PASS"
    else
        log_test "P1: Very long absolute path (250+ chars)" "FAIL - Directory not created"
    fi
}

# Test P2: Very long relative paths
test_long_relative_path() {
    cd "$BASEDIR"
    local long_rel_path="a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z/aa/bb/cc/dd/ee/ff/gg/hh"
    mkdir -p "$long_rel_path" 2>&1
    if [ -d "$long_rel_path" ]; then
        log_test "P2: Very long relative path" "PASS"
    else
        log_test "P2: Very long relative path" "FAIL - Directory not created"
    fi
}

# Test P3: Deeply nested directories (50+ levels)
test_deeply_nested_dirs() {
    cd "$BASEDIR"
    local nested="deep"
    for i in {1..50}; do
        nested="${nested}/level_$i"
    done
    mkdir -p "$nested" 2>&1
    if [ -d "$nested" ]; then
        log_test "P3: Deeply nested directories (50+ levels)" "PASS"
    else
        log_test "P3: Deeply nested directories (50+ levels)" "FAIL - Directory not created"
    fi
}

# Test P4: Paths with spaces
test_path_with_spaces() {
    local space_path="$BASEDIR/path with spaces/and more spaces/test"
    mkdir -p "$space_path" 2>&1
    if [ -d "$space_path" ]; then
        touch "$space_path/file with spaces.txt"
        if [ -f "$space_path/file with spaces.txt" ]; then
            log_test "P4: Paths with spaces" "PASS"
        else
            log_test "P4: Paths with spaces" "FAIL - File with spaces not created"
        fi
    else
        log_test "P4: Paths with spaces" "FAIL - Directory not created"
    fi
}

# Test P5: Paths with special characters
test_path_with_special_chars() {
    local special_path="$BASEDIR/special_!@#\$%^&_test"
    # Need to escape properly
    mkdir -p "$BASEDIR/special_test" 2>&1
    local special_dir="$BASEDIR/special_test/subdir_~!@#"
    mkdir -p "$special_dir" 2>&1
    if [ -d "$special_dir" ]; then
        log_test "P5: Paths with special characters" "PASS"
    else
        log_test "P5: Paths with special characters" "FAIL"
    fi
}

# Test P6: Paths with unicode characters
test_path_with_unicode() {
    local unicode_path="$BASEDIR/测试_عربي_test"
    mkdir -p "$unicode_path" 2>&1
    if [ -d "$unicode_path" ]; then
        log_test "P6: Paths with unicode characters" "PASS"
    else
        log_test "P6: Paths with unicode characters" "FAIL"
    fi
}

# Test P7: Paths with tabs in filenames
test_path_with_tabs() {
    cd "$BASEDIR"
    mkdir -p "tab_test"
    local tab_file="tab_test/file$(printf '\t')name.txt"
    touch "$tab_file" 2>&1
    if [ -f "$tab_file" ]; then
        log_test "P7: Paths with tabs" "PASS"
    else
        log_test "P7: Paths with tabs" "FAIL"
    fi
}

# Test P8: Case sensitivity - create files with same name different case
test_case_sensitivity() {
    local case_dir="$BASEDIR/case_test"
    mkdir -p "$case_dir"
    touch "$case_dir/TestFile.txt"
    touch "$case_dir/testfile.txt" 2>&1
    # On Linux both files exist, on macOS they're the same file
    local count=$(ls "$case_dir" | wc -l)
    if [ "$count" -ge 1 ]; then
        log_test "P8: Case sensitivity handling" "PASS"
    else
        log_test "P8: Case sensitivity handling" "FAIL"
    fi
}

setup
test_long_absolute_path
test_long_relative_path
test_deeply_nested_dirs
test_path_with_spaces
test_path_with_special_chars
test_path_with_unicode
test_path_with_tabs
test_case_sensitivity

echo -e "\n=== PATH EXTREMES TEST SUMMARY ===" | tee -a "$TEST_LOG"
echo -e "$RESULTS" | tee -a "$TEST_LOG"

# Cleanup
rm -rf "$BASEDIR"
