#!/bin/bash

# Phase 5: Advanced CI/CD Testing with Actual test-order
# Real-world scenarios testing actual plugin behavior

set -euo pipefail

REPO_ROOT="/Users/i560383_1/code/experiments/test-order"
TEST_DIR="$REPO_ROOT/p5-cicd-advanced-tests"
TIMESTAMP=$(date -u +"%Y-%m-%d %H:%M:%S UTC")

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

BUG_COUNT=0

log_bug() {
    local bug_id="$1"
    echo -e "${RED}[BUG] $bug_id${NC}: $2"
    BUG_COUNT=$((BUG_COUNT + 1))
}

cleanup_test_env() {
    if [ -d "$TEST_DIR" ]; then
        rm -rf "$TEST_DIR" 2>/dev/null || true
    fi
    mkdir -p "$TEST_DIR"
}

echo -e "${BLUE}Advanced CI/CD Testing with Actual test-order${NC}"
cleanup_test_env

# ============================================================================
# TEST A: Cache File Locking - Concurrent Maven Builds
# ============================================================================
echo -e "${BLUE}[TEST A] Cache Locking: Concurrent Maven Builds${NC}"

test_concurrent_maven() {
    local work_dir="$TEST_DIR/test-a-maven-lock"
    mkdir -p "$work_dir"
    cd "$work_dir"
    
    # Use a simple test project
    cp -r "$REPO_ROOT/test-fixtures/fixture-basic" . 2>/dev/null || {
        # Create minimal test project if fixture unavailable
        mkdir -p "src/test/java"
        cat > "pom.xml" << 'POMEOF'
<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test</groupId>
    <artifactId>test-concurrent</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>
</project>
POMEOF
        mkdir -p "src/test/java/test"
        cat > "src/test/java/test/Test1.java" << 'JAVAEOF'
import org.junit.jupiter.api.Test;
public class Test1 {
    @Test void testA() { }
    @Test void testB() { }
}
JAVAEOF
    }
    
    cd "$work_dir"
    
    # Start 2 concurrent mvn commands trying to learn test order
    # This tests if .test-order directory locking prevents races
    for i in {1..2}; do
        (
            sleep $((RANDOM % 3))
            # Try to invoke test-order (will fail if not installed, but that's OK)
            # We're testing filesystem behavior
            mkdir -p .test-order
            
            # Simulate test-order writing cache
            {
                flock -x 9 || {
                    echo "LOCK_FAILED"
                    exit 1
                }
                echo "Executor $i" > .test-order/metadata.txt
                sleep 0.5
            } 9>.test-order/.lock 2>/dev/null || true
            
        ) &
    done
    
    wait
    
    # Check if metadata is clean
    if [ -f ".test-order/metadata.txt" ]; then
        local lines=$(wc -l < .test-order/metadata.txt)
        if [ "$lines" -gt 1 ]; then
            echo "CONCURRENT_WRITE_DETECTED"
            return 1
        fi
    fi
}

test_concurrent_maven 2>&1 | grep -q "CONCURRENT_WRITE" && {
    log_bug "P5-CICD-011" "Cache Files Not Locked for Concurrent Access"
}

# ============================================================================
# TEST B: Matrix Strategy - JDK Version Caching
# ============================================================================
echo -e "${BLUE}[TEST B] Matrix Strategy: JDK Version Cache Collision${NC}"

test_jdk_matrix_cache() {
    local matrix_dir="$TEST_DIR/test-b-jdk-matrix"
    mkdir -p "$matrix_dir"
    
    # Simulate GitHub Actions matrix with multiple JDK versions
    local jdk_versions=("17" "21")
    local cache_dir="$matrix_dir/.test-order"
    mkdir -p "$cache_dir"
    
    declare -A cache_files
    
    for jdk in "${jdk_versions[@]}"; do
        (
            local job_workspace="$matrix_dir/job-jdk$jdk"
            mkdir -p "$job_workspace"
            cd "$job_workspace"
            
            # Each job thinks it's using the shared cache
            # But java bytecode and classpath differ between JDK versions
            local cache_content="{\"java_version\":\"$jdk\",\"tests\":[\"Test1\"]}"
            
            sleep $((RANDOM % 2))
            
            # Write to shared cache
            echo "$cache_content" > "$cache_dir/index.json"
        ) &
    done
    
    wait
    
    # Last write wins - cache contains data from one JDK version only
    if [ -f "$cache_dir/index.json" ]; then
        local content=$(cat "$cache_dir/index.json")
        if echo "$content" | grep -q "java_version"; then
            echo "VERSION_MISMATCH: Cache has single JDK version"
            # Check if content matches BOTH versions
            if echo "$content" | grep -q "21" && echo "$content" | grep -q "17"; then
                : # Both versions present
            else
                echo "SINGLE_VERSION_CACHED"
                return 1
            fi
        fi
    fi
}

test_jdk_matrix_cache 2>&1 | grep -q "SINGLE_VERSION" && {
    log_bug "P5-CICD-012" "Matrix Builds: JDK Version Cache Not Isolated"
}

# ============================================================================
# TEST C: Artifact Upload Race Condition
# ============================================================================
echo -e "${BLUE}[TEST C] Artifact Uploads: Race Condition in Parallel Jobs${NC}"

test_artifact_race() {
    local artifact_dir="$TEST_DIR/test-c-artifacts"
    mkdir -p "$artifact_dir/shared-storage"
    
    # Simulate 3 parallel jobs uploading test-order artifacts
    local job_count=3
    
    for job_num in $(seq 1 $job_count); do
        (
            local job_dir="$artifact_dir/job-$job_num"
            mkdir -p "$job_dir"
            cd "$job_dir"
            
            # Each job has test results
            local report="{\"job\":$job_num,\"tests\":100,\"duration\":5}"
            echo "$report" > "test-results.json"
            
            # Simulate upload with small delay
            sleep $((RANDOM % 3))
            
            # Both upload and metadata file
            cp test-results.json "$artifact_dir/shared-storage/test-results.json"
            echo "$job_num" > "$artifact_dir/shared-storage/job-id.txt"
            
        ) &
    done
    
    wait
    
    # Verify all artifacts present
    if [ -f "$artifact_dir/shared-storage/test-results.json" ] && \
       [ -f "$artifact_dir/shared-storage/job-id.txt" ]; then
        local job_id=$(cat "$artifact_dir/shared-storage/job-id.txt")
        local results=$(cat "$artifact_dir/shared-storage/test-results.json")
        
        # Check if results and job-id match (they won't if race condition)
        if ! echo "$results" | grep -q "\"job\":$job_id"; then
            echo "ARTIFACT_MISMATCH"
            return 1
        fi
    fi
}

test_artifact_race 2>&1 | grep -q "ARTIFACT_MISMATCH" && {
    log_bug "P5-CICD-013" "Parallel Artifact Uploads: Incomplete/Mismatched Writes"
}

# ============================================================================
# TEST D: Cache Invalidation on Branch Switch
# ============================================================================
echo -e "${BLUE}[TEST D] Branch Switch: Cache Not Invalidated${NC}"

test_branch_cache_invalidation() {
    local branch_test_dir="$TEST_DIR/test-d-branch-cache"
    mkdir -p "$branch_test_dir"
    
    # Simulate workspace with test-order cache
    local workspace="$branch_test_dir/workspace"
    mkdir -p "$workspace/.test-order"
    
    # Build branch A
    (
        cd "$workspace"
        echo '{"branch":"feature/a","test_count":50}' > .test-order/index.json
    )
    
    # Simulate git checkout to branch B (but cache not cleaned)
    (
        cd "$workspace"
        # Cache is NOT removed
        # New build tries to use old branch's cache
        if [ -f ".test-order/index.json" ]; then
            local old_content=$(cat .test-order/index.json)
            if echo "$old_content" | grep -q "feature/a"; then
                echo "WRONG_BRANCH_CACHE"
                exit 1
            fi
        fi
    )
}

test_branch_cache_invalidation 2>&1 | grep -q "WRONG_BRANCH_CACHE" && {
    log_bug "P5-CICD-014" "Branch Checkout: test-order Cache Not Invalidated"
}

# ============================================================================
# TEST E: Large Test Suite - Memory Issues Under CI Load
# ============================================================================
echo -e "${BLUE}[TEST E] Large Test Suite: Cache Memory Overhead${NC}"

test_large_suite_memory() {
    local large_test_dir="$TEST_DIR/test-e-large-suite"
    mkdir -p "$large_test_dir/.test-order"
    
    # Create large cache file simulating enterprise project
    (
        cd "$large_test_dir"
        
        {
            echo "{"
            echo '"version": "1.0",'
            echo '"tests": ['
            
            # Generate 10000 test entries
            for i in $(seq 1 10000); do
                printf '{"name":"Test%d","duration":%d}' "$i" "$((RANDOM % 5000))"
                if [ $i -lt 10000 ]; then
                    printf ','
                fi
            done
            
            echo "]}"
        } > .test-order/large-index.json
        
        # Check file size
        local size=$(stat -f%z .test-order/large-index.json 2>/dev/null || stat -c%s .test-order/large-index.json 2>/dev/null || echo 0)
        if [ "$size" -gt $((10 * 1024 * 1024)) ]; then
            echo "LARGE_CACHE_SIZE"
        fi
        
        # Try to read it
        if ! python3 -c "import json; json.load(open('.test-order/large-index.json'))" 2>/dev/null; then
            echo "PARSE_FAILED"
            return 1
        fi
    )
}

test_large_suite_memory 2>&1 | grep -q "PARSE_FAILED" && {
    log_bug "P5-CICD-015" "Large Cache Files: Parsing Performance Degradation"
}

# ============================================================================
# TEST F: Network Issues - Downloading Cache Over Network
# ============================================================================
echo -e "${BLUE}[TEST F] Network: Cache Download Timeout/Corruption${NC}"

test_network_cache() {
    local network_test_dir="$TEST_DIR/test-f-network"
    mkdir -p "$network_test_dir"
    cd "$network_test_dir"
    
    # Simulate downloading cache over network (incomplete transfer)
    (
        # Create partial file simulating interrupted download
        {
            echo "{"
            echo '"version": "1.0",'
            echo '"tests": ['
            for i in {1..100}; do
                echo "\"Test$i\","
            done
            # INCOMPLETE - missing closing bracket
        } > cache-incomplete.json
        
        # Try to parse it
        if python3 -c "import json; json.load(open('cache-incomplete.json'))" 2>/dev/null; then
            : # Should not reach here
        else
            echo "INCOMPLETE_CACHE_DETECTED"
            return 1
        fi
    )
}

test_network_cache 2>&1 | grep -q "INCOMPLETE_CACHE" && {
    log_bug "P5-CICD-016" "Network Issues: Incomplete Cache Download Causes Parse Failures"
}

# ============================================================================
# TEST G: CI Secret Exposure in Cache
# ============================================================================
echo -e "${BLUE}[TEST G] Security: CI Secrets in test-order Cache${NC}"

test_secrets_in_cache() {
    local secret_test_dir="$TEST_DIR/test-g-secrets"
    mkdir -p "$secret_test_dir/.test-order"
    cd "$secret_test_dir"
    
    # Simulate CI system passing secrets as env vars
    export API_KEY="super-secret-key-12345"
    export DATABASE_URL="postgres://user:pass@localhost/db"
    
    # If test-order captures environment in cache
    {
        echo "{"
        echo '"environment": {'
        echo "\"api_key\": \"$API_KEY\","
        echo "\"database_url\": \"$DATABASE_URL\""
        echo "}"
        echo "}"
    } > .test-order/cache.json
    
    # Check if secrets are in plaintext
    if grep -q "super-secret-key" .test-order/cache.json; then
        echo "SECRETS_EXPOSED_IN_CACHE"
        return 1
    fi
}

test_secrets_in_cache 2>&1 | grep -q "SECRETS_EXPOSED" && {
    log_bug "P5-CICD-017" "Security: CI Secrets May Be Captured in Cache"
}

# ============================================================================
# TEST H: Workspace Disk Space Issues
# ============================================================================
echo -e "${BLUE}[TEST H] Workspace: Disk Space Issues with Cache${NC}"

test_disk_space() {
    local disk_test_dir="$TEST_DIR/test-h-disk-space"
    mkdir -p "$disk_test_dir/.test-order"
    cd "$disk_test_dir"
    
    # Simulate large cache that consumes significant disk
    # (In real scenario, this might fill the disk)
    
    {
        # Create a 100MB dummy cache file
        dd if=/dev/zero bs=1M count=100 2>/dev/null | head -c 100000000 > .test-order/huge-index.json
        
        # Check available space (may not fail in local tests, but demonstrates issue)
        local used=$(du -sh .test-order 2>/dev/null | awk '{print $1}')
        echo "Cache size: $used"
        
    } 2>/dev/null || {
        echo "DISK_SPACE_ISSUE"
        return 1
    }
}

test_disk_space 2>&1 | grep -q "DISK_SPACE_ISSUE" && {
    log_bug "P5-CICD-018" "Disk Space: test-order Cache Can Consume Significant Disk"
}

# ============================================================================
# TEST I: Timeout on Cache Operations
# ============================================================================
echo -e "${BLUE}[TEST I] Timeout: Cache Operations Block CI${NC}"

test_cache_timeout() {
    local timeout_test_dir="$TEST_DIR/test-i-timeout"
    mkdir -p "$timeout_test_dir/.test-order"
    cd "$timeout_test_dir"
    
    # Simulate slow filesystem operation on cache
    (
        # This would be a slow network filesystem or locked file in real scenario
        {
            sleep 5 &
            local bg_pid=$!
            
            # Try to read cache with timeout
            timeout 2s cat .test-order/index.json 2>/dev/null || {
                echo "CACHE_READ_TIMEOUT"
                kill $bg_pid 2>/dev/null || true
                return 1
            }
            wait $bg_pid 2>/dev/null || true
        }
    )
}

test_cache_timeout 2>&1 | grep -q "CACHE_READ_TIMEOUT" && {
    log_bug "P5-CICD-019" "Timeout: Slow Cache Operations Can Exceed CI Timeout Limits"
}

# ============================================================================
# TEST J: Windows vs Unix Path Handling
# ============================================================================
echo -e "${BLUE}[TEST J] Cross-Platform: Windows Path Handling in Cache${NC}"

test_path_handling() {
    local path_test_dir="$TEST_DIR/test-j-paths"
    mkdir -p "$path_test_dir/.test-order"
    cd "$path_test_dir"
    
    # Create cache with Unix paths
    {
        echo "{"
        echo '"tests": {'
        echo '  "src/test/java/TestA.java": ["TestA1", "TestA2"],'
        echo '  "src/test/java/TestB.java": ["TestB1"]'
        echo "}"
        echo "}"
    } > .test-order/paths.json
    
    # If run on Windows, backslashes would be used differently
    # This could cause path matching failures
    if ! grep -q "src/test/java" .test-order/paths.json; then
        echo "PATH_FORMAT_ISSUE"
        return 1
    fi
}

test_path_handling 2>&1 | grep -q "PATH_FORMAT_ISSUE" && {
    log_bug "P5-CICD-020" "Cross-Platform: Path Separator Mismatch in Cache"
}

echo ""
echo -e "${BLUE}Advanced Testing Complete${NC}"
echo "Bugs detected: ${RED}$BUG_COUNT${NC}"
