#!/bin/bash

LOGFILE="MMAD-test-results.log"
ERRORS=""
BUGS_FOUND=0

log() {
    echo "[$(date +'%H:%M:%S')] $1" | tee -a "$LOGFILE"
}

test_case() {
    local name="$1"
    local cmd="$2"
    log ""
    log "=== TEST: $name ==="
    log "Command: $cmd"
    eval "$cmd" >> "$LOGFILE" 2>&1
    local result=$?
    if [ $result -eq 0 ]; then
        log "✓ PASSED"
    else
        log "✗ FAILED (exit code: $result)"
        ERRORS="$ERRORS\n- $name (exit code: $result)"
    fi
    return $result
}

report_bug() {
    local id="$1"
    local title="$2"
    BUGS_FOUND=$((BUGS_FOUND + 1))
    log "🐛 BUG FOUND: $id - $title"
    ERRORS="$ERRORS\n✗ BUG: $id - $title"
}

# Clean start
rm -f "$LOGFILE"
log "Starting Phase 5 MMAD comprehensive testing"

# TEST 1: Basic reactor build
test_case "Basic reactor build" "mvn clean install > /dev/null 2>&1"

# TEST 2: Full test run
test_case "Full test run with test-order" "mvn clean test > /dev/null 2>&1"

# TEST 3: Test ordering consistency
log ""
log "=== TEST: Test ordering consistency ==="
log "Running first test order..."
mvn clean test -q 2>&1 | grep "Running" > /tmp/order1.txt
log "Running second test order..."
mvn test -q 2>&1 | grep "Running" > /tmp/order2.txt
if diff /tmp/order1.txt /tmp/order2.txt > /dev/null; then
    log "✓ Test order is consistent"
else
    log "✗ Test order inconsistency detected!"
    report_bug "P5-MMAD-002" "Test ordering inconsistency across runs"
fi

# TEST 4: Cache reuse
log ""
log "=== TEST: Cache reuse detection ==="
FIRST_BUILD=$(mvn clean test -q 2>&1 | grep "Total time:" | grep -oE "[0-9]+\.[0-9]+ s")
SECOND_BUILD=$(mvn test -q 2>&1 | grep "Total time:" | grep -oE "[0-9]+\.[0-9]+ s")
log "First build time: $FIRST_BUILD"
log "Second build time (should use cache): $SECOND_BUILD"

# TEST 5: Module with changed source
log ""
log "=== TEST: Cache invalidation on module change ==="
echo "// Added comment" >> service-a/src/main/java/com/example/service/a/ServiceA.java
mvn service-a:test -q 2>&1 | grep -q "Learn mode\|changed"
if [ $? -eq 0 ]; then
    log "✓ Cache invalidation working (detected change)"
else
    log "⚠ Could not verify cache invalidation"
fi
git checkout service-a/src/main/java/com/example/service/a/ServiceA.java 2>/dev/null || true

# TEST 6: Resume from module with -rf
log ""
log "=== TEST: Resume-from flag (-rf) ==="
test_case "Resume from service-a" "mvn clean && mvn -rf :service-a test -q"

# TEST 7: Build only requested with -am
log ""
log "=== TEST: Also-make flag (-am) ==="
test_case "Build only app and deps" "mvn clean && mvn -am -pl :app test -q"

# TEST 8: Parallel build
log ""
log "=== TEST: Parallel build with -T ==="
test_case "Parallel build (2 threads)" "mvn clean test -T 2 -q"

# TEST 9: Test filtering across modules
log ""
log "=== TEST: Test filtering ==="
test_case "Run only specific test" "mvn test -Dtest=CoreTest1 -q"

# TEST 10: Skip tests flag
log ""
log "=== TEST: Skip tests integration ==="
test_case "Maven skip tests" "mvn test -DskipTests -q"

log ""
log "==========================================="
log "TESTING COMPLETE"
log "Bugs found: $BUGS_FOUND"
log "==========================================="

if [ -n "$ERRORS" ]; then
    echo -e "\nERRORS/BUGS FOUND:$ERRORS"
    exit 1
fi
exit 0
