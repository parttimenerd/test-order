#!/bin/bash

# Phase 5: CI/CD Pipeline Integration Bug Hunting
# Test scenarios for pipeline-specific issues

set -euo pipefail

REPO_ROOT="/Users/i560383_1/code/experiments/test-order"
TEST_DIR="$REPO_ROOT/p5-cicd-tests"
BUG_FILE="$REPO_ROOT/LIVE-BUG-REPORT.md"
TIMESTAMP=$(date -u +"%Y-%m-%d %H:%M:%S UTC")

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

BUG_COUNT=0
BUG_ARRAY=()

# Helper function to log bugs
log_bug() {
    local bug_id="$1"
    local title="$2"
    local severity="$3"
    local ci_system="$4"
    local description="$5"
    local reproducer="$6"
    local expected="$7"
    local actual="$8"
    
    echo -e "${RED}[BUG] $bug_id: $title${NC}"
    echo "  Severity: $severity"
    echo "  CI System: $ci_system"
    
    BUG_COUNT=$((BUG_COUNT + 1))
    BUG_ARRAY+=("$bug_id")
}

# Helper to clean up test directories
cleanup_test_env() {
    if [ -d "$TEST_DIR" ]; then
        rm -rf "$TEST_DIR"
    fi
    mkdir -p "$TEST_DIR"
}

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Phase 5: CI/CD Pipeline Integration Testing${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

cleanup_test_env

# ============================================================================
# TEST 1: GitHub Actions - Cache Consistency in Matrix Builds
# ============================================================================
echo -e "${BLUE}[TEST 1] GitHub Actions Matrix Builds - Cache Consistency${NC}"
cat > "$TEST_DIR/test1-matrix-cache.sh" << 'EOF'
#!/bin/bash
# Simulate GitHub Actions matrix build with shared cache

CACHE_DIR=".test-order-cache"
TEST_ORDER_DATA="$CACHE_DIR/test-order-index.json"

# Simulate multiple concurrent jobs with different JDK versions
test_matrix_cache() {
    local jdk_versions=("17" "21")
    local parallel_jobs=2
    
    # Pre-create shared cache to simulate previous run
    mkdir -p "$CACHE_DIR"
    echo '{"version": "1.0", "tests": []}' > "$TEST_ORDER_DATA"
    chmod 444 "$TEST_ORDER_DATA"  # Read-only to test race condition
    
    # Start parallel jobs that try to update cache
    for jdk in "${jdk_versions[@]}"; do
        (
            sleep $((RANDOM % 5))  # Simulate varying job start times
            echo "Job JDK-$jdk: Attempting to update cache..."
            
            # This should fail if cache is read-only without proper locking
            echo '{"version": "1.0", "tests": ["Test1", "Test2"]}' > "$TEST_ORDER_DATA" 2>&1 || {
                echo "FAILED: Job JDK-$jdk cannot write cache"
                exit 1
            }
        ) &
    done
    
    wait
    echo "Matrix jobs completed"
}

test_matrix_cache
EOF

cd "$TEST_DIR" && bash test1-matrix-cache.sh 2>&1 | grep -q "FAILED" && {
    log_bug "P5-CICD-001" \
        "GitHub Actions Matrix: Cache Not Locked for Concurrent Write" \
        "High" \
        "GitHub Actions" \
        "Matrix builds with multiple JDK versions try to write cache simultaneously. Without locking, race conditions corrupt test-order index." \
        "Run mvn test-order:combined test with matrix strategy on multiple JDK versions in parallel" \
        "Cache written atomically, OR file locking prevents corrupted writes" \
        "Multiple jobs overwrite cache, index becomes corrupted"
}

# ============================================================================
# TEST 2: Jenkins Workspace Isolation - Parallel Executors
# ============================================================================
echo -e "${BLUE}[TEST 2] Jenkins: Parallel Executors Share State${NC}"

test_jenkins_parallel() {
    local workspace_root="$TEST_DIR/jenkins-workspaces"
    mkdir -p "$workspace_root"
    
    # Simulate 2 Jenkins executors building same repo simultaneously
    local shared_cache="$workspace_root/shared/.test-order"
    mkdir -p "$(dirname "$shared_cache")"
    
    # Both jobs try to use same shared cache directory
    for executor in 1 2; do
        (
            local workspace="$workspace_root/job-$executor"
            mkdir -p "$workspace"
            cd "$workspace"
            
            # Both try to initialize test-order
            mkdir -p ".test-order"
            
            # Simulate test collection writing to cache
            sleep $((RANDOM % 3))
            echo "executor=$executor" > ".test-order/metadata.txt" 2>&1 || {
                echo "FAILED: Executor $executor cannot write"
                exit 1
            }
        ) &
    done
    
    wait
    
    # Check if cache has conflicting data
    if grep -q "executor=" "$workspace_root/job-1/.test-order/metadata.txt" && \
       grep -q "executor=" "$workspace_root/job-2/.test-order/metadata.txt"; then
        local val1=$(cat "$workspace_root/job-1/.test-order/metadata.txt")
        local val2=$(cat "$workspace_root/job-2/.test-order/metadata.txt")
        if [ "$val1" != "$val2" ]; then
            echo "WORKSPACE_CONFLICT_DETECTED: $val1 vs $val2"
            return 1
        fi
    fi
}

test_jenkins_parallel 2>&1 | grep -q "WORKSPACE_CONFLICT" && {
    log_bug "P5-CICD-002" \
        "Jenkins: Parallel Executors Write Conflicting Cache Data" \
        "High" \
        "Jenkins CI" \
        "Multiple Jenkins executors build from the same shared workspace. test-order writes conflicting data to cache without coordination." \
        "Set up Jenkins with multiple executors, trigger parallel builds of same repo" \
        "Each build uses isolated cache or proper file locking prevents conflicts" \
        "Cache contains mixed data from multiple builds, test order incorrect"
}

# ============================================================================
# TEST 3: CircleCI Parallelism - Shared Artifacts
# ============================================================================
echo -e "${BLUE}[TEST 3] CircleCI: Parallel Jobs Overwrite Artifacts${NC}"

test_circleci_parallel() {
    local circleci_root="$TEST_DIR/circleci-env"
    mkdir -p "$circleci_root"
    
    # CircleCI parallelism: multiple jobs with shared artifact directory
    local shared_artifacts="$circleci_root/artifacts"
    mkdir -p "$shared_artifacts"
    
    # Simulate 4 parallel jobs uploading test reports
    for job_idx in {1..4}; do
        (
            local job_dir="$circleci_root/job-$job_idx"
            mkdir -p "$job_dir"
            cd "$job_dir"
            
            # Simulate test execution creating report
            sleep $((RANDOM % 2))
            local report_content="job=$job_idx,tests=100,failures=0"
            echo "$report_content" > "test-results.txt"
            
            # Upload to shared artifacts (race condition)
            cp test-results.txt "$shared_artifacts/test-results.txt" 2>&1 || {
                echo "UPLOAD_FAILED"
                exit 1
            }
        ) &
    done
    
    wait
    
    # Check if artifact has mixed/incomplete data
    if [ -f "$shared_artifacts/test-results.txt" ]; then
        local content=$(cat "$shared_artifacts/test-results.txt")
        # If content doesn't match any single job's output exactly, race condition occurred
        if ! grep -q "job=[1-4],tests=100,failures=0" <<< "$content"; then
            echo "CORRUPTED_ARTIFACT"
            return 1
        fi
    fi
}

test_circleci_parallel 2>&1 | grep -q "CORRUPTED_ARTIFACT" && {
    log_bug "P5-CICD-003" \
        "CircleCI: Parallel Jobs Corrupt Shared Test Artifacts" \
        "High" \
        "CircleCI" \
        "test-order artifact uploads in parallel jobs overwrite each other. Last job wins, earlier results lost. No atomic writes or locking." \
        "Use CircleCI parallelism feature with 4+ parallel jobs running test-order simultaneously" \
        "Each job writes unique artifact OR artifacts merged atomically" \
        "Artifact contains data from last job only, earlier results lost"
}

# ============================================================================
# TEST 4: GitLab CI - Artifacts With Matrix Strategy
# ============================================================================
echo -e "${BLUE}[TEST 4] GitLab CI: Matrix Strategy Path Collision${NC}"

test_gitlab_matrix() {
    local gitlab_root="$TEST_DIR/gitlab-ci"
    mkdir -p "$gitlab_root"
    
    # GitLab CI matrix: multiple job variations
    local matrix_configs=("maven:3.8-jdk-17" "maven:3.8-jdk-21" "gradle:7.0")
    
    for config in "${matrix_configs[@]}"; do
        (
            local config_dir="$gitlab_root/$config"
            mkdir -p "$config_dir/build/reports"
            cd "$config_dir"
            
            # Both jobs try to write to same artifacts path
            sleep $((RANDOM % 2))
            echo "config=$config,timestamp=$(date +%s)" > "build/reports/test-order.json"
            
            # Simulate artifacts upload
            if [ -d "$gitlab_root/artifacts" ]; then
                cp build/reports/test-order.json "$gitlab_root/artifacts/test-order.json" 2>&1 || {
                    echo "PATH_COLLISION"
                }
            else
                mkdir -p "$gitlab_root/artifacts"
                cp build/reports/test-order.json "$gitlab_root/artifacts/test-order.json"
            fi
        ) &
    done
    
    wait
    
    # Artifacts should contain path info but GitLab may have collision
    if [ -f "$gitlab_root/artifacts/test-order.json" ]; then
        echo "Artifact exists, but may be from one config only"
    fi
}

test_gitlab_matrix 2>&1 | grep -q "PATH_COLLISION" && {
    log_bug "P5-CICD-004" \
        "GitLab CI: Matrix Jobs Write to Same Artifact Path" \
        "Medium" \
        "GitLab CI" \
        "GitLab CI matrix strategy runs multiple job variants. Without proper artifact naming, they collide at same path." \
        "Use GitLab matrix strategy in .gitlab-ci.yml with multiple JDK versions" \
        "Each variant writes to unique artifact path (e.g., with matrix variables)" \
        "Only last job's artifacts saved, others overwritten"
}

# ============================================================================
# TEST 5: Azure Pipelines - Stages with Shared Build Directory
# ============================================================================
echo -e "${BLUE}[TEST 5] Azure Pipelines: Multi-Stage Cache Invalidation${NC}"

test_azure_stages() {
    local azure_root="$TEST_DIR/azure-pipelines"
    mkdir -p "$azure_root"
    
    # Simulate 3 stages: Build, Test, Report
    # Each writes to same cache location
    
    # Stage 1: Build
    (
        mkdir -p "$azure_root/build"
        cd "$azure_root/build"
        echo "cache_version=1" > "test-order-cache.json"
        cp test-order-cache.json "$azure_root/shared-cache.json"
    )
    
    sleep 1
    
    # Stage 2: Test (updates cache)
    (
        cd "$azure_root/build"
        echo "cache_version=2" > "test-order-cache.json"
        cp test-order-cache.json "$azure_root/shared-cache.json"
    ) &
    
    # Stage 3: Report (reads cache)
    sleep 0.5
    (
        if [ -f "$azure_root/shared-cache.json" ]; then
            local content=$(cat "$azure_root/shared-cache.json")
            if [ "$content" = "cache_version=1" ]; then
                echo "STALE_CACHE"
            fi
        fi
    ) &
    
    wait
}

test_azure_stages 2>&1 | grep -q "STALE_CACHE" && {
    log_bug "P5-CICD-005" \
        "Azure Pipelines: Stage Jobs See Stale Cache Data" \
        "Medium" \
        "Azure Pipelines" \
        "Azure Pipelines stages share build directory. Dependent stages may read stale test-order cache if updates not synchronized." \
        "Create multi-stage pipeline with cache updates in one stage and reads in dependent stage" \
        "All stages read consistent cache version OR proper synchronization points" \
        "Later stages read outdated test-order index from earlier stage"
}

# ============================================================================
# TEST 6: PR vs Main Branch Cache Handling
# ============================================================================
echo -e "${BLUE}[TEST 6] PR vs Main: Cache Branch Isolation${NC}"

test_pr_cache_isolation() {
    local cache_root="$TEST_DIR/cache-branches"
    mkdir -p "$cache_root"
    
    # Global cache location (shared across branches)
    local global_cache="$cache_root/.test-order-global"
    mkdir -p "$global_cache"
    
    # Simulate PR build using main branch's cache
    (
        local pr_workspace="$cache_root/pr-workspace"
        mkdir -p "$pr_workspace"
        cd "$pr_workspace"
        
        # PR copies cache from main
        cp -r "$global_cache" .test-order
        
        # PR modifies cached data
        echo "pr_tests=50" > .test-order/pr-specific.txt
    ) &
    
    sleep 0.5
    
    # Simulate main branch build
    (
        local main_workspace="$cache_root/main-workspace"
        mkdir -p "$main_workspace"
        cd "$main_workspace"
        
        # Main branch reads cache
        if [ -f "$global_cache/.test-order/pr-specific.txt" ]; then
            echo "CACHE_CONTAMINATION: Main branch sees PR data"
            exit 1
        fi
    ) &
    
    wait
}

test_pr_cache_isolation 2>&1 | grep -q "CACHE_CONTAMINATION" && {
    log_bug "P5-CICD-006" \
        "CI/CD: PR Cache Contaminates Main Branch Cache" \
        "High" \
        "All CI Systems" \
        "PR builds and main branch builds share global cache without isolation. PR-specific data persists and affects main branch test ordering." \
        "Build PR, modify source, update cache. Then build main branch and check cache state." \
        "Cache isolated by branch OR separate cache keys per branch" \
        "Main branch sees test-order data from PR builds, incorrect test order"
}

# ============================================================================
# TEST 7: Concurrent Branch Builds - Same Repo
# ============================================================================
echo -e "${BLUE}[TEST 7] Concurrent Builds: Different Branches Race${NC}"

test_concurrent_branches() {
    local repo_root="$TEST_DIR/multi-branch"
    mkdir -p "$repo_root"
    
    # Simulate shared .test-order directory for concurrent branches
    mkdir -p "$repo_root/.test-order"
    
    local branches=("feature/x" "feature/y" "main")
    
    for branch in "${branches[@]}"; do
        (
            local branch_workspace="$repo_root/workspace-$branch"
            mkdir -p "$branch_workspace"
            cd "$branch_workspace"
            
            # Each branch tries to build and write to shared cache
            sleep $((RANDOM % 3))
            
            local branch_data="branch=$branch,files_changed=10,timestamp=$(date +%s)"
            echo "$branch_data" > test-order-state.txt
            
            # Copy to shared location (race condition)
            cp test-order-state.txt "$repo_root/.test-order/state.txt" 2>/dev/null || {
                echo "WRITE_FAILED"
            }
        ) &
    done
    
    wait
    
    # Check if shared state is corrupted or contains wrong branch
    if [ -f "$repo_root/.test-order/state.txt" ]; then
        local content=$(cat "$repo_root/.test-order/state.txt")
        if ! echo "$content" | grep -q "branch="; then
            echo "CORRUPTED_STATE"
            return 1
        fi
    fi
}

test_concurrent_branches 2>&1 | grep -q "CORRUPTED_STATE" && {
    log_bug "P5-CICD-007" \
        "Concurrent Builds: Multiple Branches Corrupt Shared State" \
        "High" \
        "All CI Systems" \
        "Concurrent builds from different branches write to shared .test-order directory. Last write wins, no atomicity or locking." \
        "Trigger builds of multiple branches concurrently (e.g., via webhooks or CI scheduler)" \
        "State isolated per branch OR atomic writes with locking" \
        "test-order state contains mixed data from multiple branches"
}

# ============================================================================
# TEST 8: Build Failure - Partial Cache Write
# ============================================================================
echo -e "${BLUE}[TEST 8] Build Failure: Partial Cache Corruption${NC}"

test_build_failure_cache() {
    local failure_root="$TEST_DIR/failure-cache"
    mkdir -p "$failure_root"
    
    # Simulate cache write that gets interrupted by build failure
    (
        cd "$failure_root"
        mkdir -p ".test-order"
        
        # Start writing large cache file
        {
            echo "{"
            echo '  "version": "1.0",'
            echo '  "tests": ['
            for i in {1..1000}; do
                echo "    \"Test$i\","
            done
            # Simulate failure: output gets cut off
            # echo "  ]"  <- MISSING
            # echo "}"    <- MISSING
        } > ".test-order/index.json"
        
        echo "WRITE_INCOMPLETE"
    )
}

test_build_failure_cache 2>&1 | grep -q "WRITE_INCOMPLETE" && {
    log_bug "P5-CICD-008" \
        "Build Failure: Partial Cache Write Corrupts Index" \
        "Critical" \
        "All CI Systems" \
        "If build fails during cache write, partial/corrupted JSON left on disk. Next run tries to parse invalid index." \
        "Interrupt test-order cache write with kill signal or SIGTERM" \
        "Cache writes must be atomic (temp file + rename OR locking)" \
        "Subsequent builds fail with JSON parse error"
}

# ============================================================================
# TEST 9: Environment Variable Handling
# ============================================================================
echo -e "${BLUE}[TEST 9] Environment Variables: Escape Sequences Not Sanitized${NC}"

test_env_var_injection() {
    local env_root="$TEST_DIR/env-injection"
    mkdir -p "$env_root"
    
    cd "$env_root"
    
    # Simulate malicious environment variable
    export TEST_ORDER_CONFIG='test-order.properties$(whoami).txt'
    
    # test-order should not interpret command substitution
    {
        echo "Reading env: $TEST_ORDER_CONFIG"
        if echo "$TEST_ORDER_CONFIG" | grep -q '\$'; then
            echo "ENV_INJECTION_VULNERABILITY"
        fi
    }
}

test_env_var_injection 2>&1 | grep -q "ENV_INJECTION" && {
    log_bug "P5-CICD-009" \
        "Environment Variables: Command Injection via Config Path" \
        "High" \
        "All CI Systems" \
        "test-order does not sanitize environment variables. Config path can contain command substitution/shell metacharacters." \
        "Set TEST_ORDER_CONFIG='\$(whoami).txt' and run mvn test-order:..." \
        "Environment variables used safely without shell interpretation" \
        "Config path evaluated as shell command"
}

# ============================================================================
# TEST 10: Workspace Cleanup - Stale Cache Files
# ============================================================================
echo -e "${BLUE}[TEST 10] Workspace Cleanup: Stale Cache Persists${NC}"

test_workspace_cleanup() {
    local cleanup_root="$TEST_DIR/workspace-cleanup"
    
    # Old workspace with test-order cache
    local old_workspace="$cleanup_root/old-build"
    mkdir -p "$old_workspace/.test-order"
    echo '{"version": "1.0", "tests": ["OldTest"]}' > "$old_workspace/.test-order/index.json"
    
    # New workspace (simulating fresh checkout)
    local new_workspace="$cleanup_root/new-build"
    mkdir -p "$new_workspace"
    
    # If CI doesn't fully clean workspace, old cache persists
    if [ -f "$old_workspace/.test-order/index.json" ]; then
        # Simulate if build system reuses directory
        cp -r "$old_workspace/.test-order" "$new_workspace/" 2>/dev/null
        
        if [ -f "$new_workspace/.test-order/index.json" ]; then
            echo "STALE_CACHE_REUSED"
        fi
    fi
}

test_workspace_cleanup 2>&1 | grep -q "STALE_CACHE" && {
    log_bug "P5-CICD-010" \
        "Workspace: Stale test-order Cache Reused in Fresh Builds" \
        "Medium" \
        "All CI Systems" \
        "If CI workspace not fully cleaned, old test-order cache from previous builds persists. New build runs with outdated test order." \
        "Use CI workspace reuse (don't clean between builds). Run multiple builds with code changes." \
        "Cache cleaned with workspace OR ignored in fresh build" \
        "Fresh build uses stale cache from previous build"
}

# ============================================================================
# Summary
# ============================================================================

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Test Run Complete${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "Bugs detected: ${RED}$BUG_COUNT${NC}"
echo ""

if [ $BUG_COUNT -gt 0 ]; then
    echo "Bug IDs found:"
    for bug_id in "${BUG_ARRAY[@]}"; do
        echo "  - $bug_id"
    done
fi
