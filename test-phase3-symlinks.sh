#!/bin/bash
# PHASE 3: Filesystem Edge Case Testing - Symbolic Links
# Test symlinks, broken links, circular links, chain of links

set -e
BASEDIR="/tmp/phase3-symlink-tests"
TEST_LOG="$BASEDIR/test-results.log"
RESULTS=""

setup() {
    rm -rf "$BASEDIR" 2>/dev/null || true
    mkdir -p "$BASEDIR"
    > "$TEST_LOG"
    echo "=== PHASE 3: Symbolic Links Testing ===" | tee "$TEST_LOG"
}

log_test() {
    local test_name="$1"
    local result="$2"
    echo "$test_name: $result" | tee -a "$TEST_LOG"
    RESULTS="${RESULTS}${test_name}: ${result}\n"
}

# Test SL1: Symlink to test directory
test_symlink_to_directory() {
    local src_dir="$BASEDIR/src_dir/test"
    local link_dir="$BASEDIR/link_to_dir"
    mkdir -p "$src_dir"
    touch "$src_dir/file.txt"
    ln -s "$src_dir" "$link_dir" 2>&1
    if [ -L "$link_dir" ] && [ -f "$link_dir/file.txt" ]; then
        log_test "SL1: Symlink to test directory" "PASS"
    else
        log_test "SL1: Symlink to test directory" "FAIL"
    fi
}

# Test SL2: Symlink to JAR file
test_symlink_to_jar() {
    local jar_file="$BASEDIR/original.jar"
    local jar_link="$BASEDIR/link.jar"
    touch "$jar_file"
    ln -s "$jar_file" "$jar_link" 2>&1
    if [ -L "$jar_link" ] && [ -f "$jar_link" ]; then
        log_test "SL2: Symlink to JAR file" "PASS"
    else
        log_test "SL2: Symlink to JAR file" "FAIL"
    fi
}

# Test SL3: Broken symlink (target deleted)
test_broken_symlink() {
    local target="$BASEDIR/will_be_deleted"
    local broken_link="$BASEDIR/broken_link"
    touch "$target"
    ln -s "$target" "$broken_link"
    rm "$target"
    if [ -L "$broken_link" ] && [ ! -e "$broken_link" ]; then
        # Symlink exists but target doesn't
        log_test "SL3: Broken symlink detection" "PASS"
    else
        log_test "SL3: Broken symlink detection" "FAIL"
    fi
}

# Test SL4: Circular symlinks
test_circular_symlink() {
    local dir_a="$BASEDIR/dir_a"
    local dir_b="$BASEDIR/dir_b"
    mkdir -p "$dir_a" "$dir_b"
    ln -s "$dir_b" "$dir_a/link_to_b" 2>&1
    ln -s "$dir_a" "$dir_b/link_to_a" 2>&1
    # Try to read - should not infinite loop
    timeout 2 ls -la "$dir_a/link_to_b/link_to_a" >/dev/null 2>&1
    if [ $? -eq 124 ]; then
        log_test "SL4: Circular symlinks" "FAIL - Infinite loop detected"
    else
        log_test "SL4: Circular symlinks" "PASS - No infinite loop"
    fi
}

# Test SL5: Symlink to parent directory
test_symlink_to_parent() {
    local parent_dir="$BASEDIR/parent"
    local child_dir="$parent_dir/child"
    mkdir -p "$child_dir"
    ln -s "../.." "$child_dir/link_to_parent" 2>&1
    if [ -L "$child_dir/link_to_parent" ]; then
        log_test "SL5: Symlink to parent directory" "PASS"
    else
        log_test "SL5: Symlink to parent directory" "FAIL"
    fi
}

# Test SL6: Chain of symlinks (A→B→C→D)
test_symlink_chain() {
    local target="$BASEDIR/final_target"
    touch "$target"
    ln -s "$target" "$BASEDIR/link_a"
    ln -s "$BASEDIR/link_a" "$BASEDIR/link_b"
    ln -s "$BASEDIR/link_b" "$BASEDIR/link_c"
    if [ -f "$BASEDIR/link_c" ]; then
        log_test "SL6: Chain of symlinks (A→B→C)" "PASS"
    else
        log_test "SL6: Chain of symlinks (A→B→C)" "FAIL"
    fi
}

# Test SL7: Symlink in source tree
test_symlink_in_src_tree() {
    local src_tree="$BASEDIR/src_tree"
    mkdir -p "$src_tree/src/test/java"
    local external_dir="$BASEDIR/external"
    mkdir -p "$external_dir"
    touch "$external_dir/TestClass.java"
    ln -s "$external_dir/TestClass.java" "$src_tree/src/test/java/LinkedTest.java"
    if [ -L "$src_tree/src/test/java/LinkedTest.java" ]; then
        log_test "SL7: Symlink in source tree" "PASS"
    else
        log_test "SL7: Symlink in source tree" "FAIL"
    fi
}

setup
test_symlink_to_directory
test_symlink_to_jar
test_broken_symlink
test_circular_symlink
test_symlink_to_parent
test_symlink_chain
test_symlink_in_src_tree

echo -e "\n=== SYMBOLIC LINKS TEST SUMMARY ===" | tee -a "$TEST_LOG"
echo -e "$RESULTS" | tee -a "$TEST_LOG"

# Cleanup
rm -rf "$BASEDIR"
