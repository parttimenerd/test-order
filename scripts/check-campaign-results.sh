#!/bin/bash
# Script to collect and analyze campaign results when thinkstation comes back online
# Usage: Run this after connectivity is restored

set -euo pipefail

echo "Collecting campaign results..."
echo "=============================="
echo ""

RESULTS_DIR=~/code/test-order/target/third-party-results

# Function to check campaign result
check_campaign() {
    local repo=$1
    local log_file=$2

    if [ ! -f "$log_file" ]; then
        echo "$repo: NO LOG FILE FOUND"
        return
    fi

    # Check for completion
    if grep -q "Full workflow completed" "$log_file" 2>/dev/null; then
        echo "$repo: COMPLETE"

        # Extract bug detection result
        if grep -q "Bug caught in top-3\|Bug not caught in top-3" "$log_file" 2>/dev/null; then
            if grep -q "Bug caught in top-3" "$log_file"; then
                echo "  → Step 7: CAUGHT ✓"
            else
                echo "  → Step 7: MISSED"
            fi
        fi

        if grep -q "SA auto-mode caught bug\|SA auto-mode failed" "$log_file" 2>/dev/null; then
            if grep -q "SA auto-mode caught bug" "$log_file"; then
                echo "  → Step 7b: CAUGHT ✓"
            else
                echo "  → Step 7b: MISSED"
            fi
        fi

        # Extract test count
        grep "Tests run:" "$log_file" | tail -1 | sed 's/^/  → /'

    elif grep -q "BUILD FAILED\|^ERROR" "$log_file" 2>/dev/null; then
        echo "$repo: BUILD FAILED"
        tail -5 "$log_file" | sed 's/^/  /'
    else
        # Still running
        step=$(grep -o 'Step [0-9]\+' "$log_file" | tail -1 || echo "Unknown")
        echo "$repo: RUNNING ($step)"
    fi
    echo ""
}

# Check each campaign
echo "CAMPAIGN RESULTS:"
echo "================"
echo ""

check_campaign "kafka" /tmp/campaign-kafka.log
check_campaign "maven" /tmp/campaign-maven.log
check_campaign "ai-sdk-java" /tmp/campaign-ai-sdk-java.log
check_campaign "commons-codec-3" /tmp/campaign-commons-codec-3.log

echo ""
echo "RETRIEVING OFFICIAL RESULTS FROM TEST-ORDER:"
echo "=============================================="
echo ""

# Check for completed result directories
for repo in kafka maven ai-sdk-java commons-codec; do
    result_dir=$(ls -td $RESULTS_DIR/$repo/*/full-bug-select.log 2>/dev/null | head -1 | xargs dirname 2>/dev/null || true)
    if [ -n "$result_dir" ]; then
        echo "$repo result directory: $result_dir"

        tests=$(grep "Tests run:" "$result_dir/full-bug-select.log" | tail -1 | grep -o "Failures: [0-9]*" || echo "0")
        echo "  → $tests"
        echo ""
    fi
done

echo "Done!"
