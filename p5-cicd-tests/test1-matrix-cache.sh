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
