#!/bin/bash

# Phase 5: Real-World Project Testing
# Test test-order against actual open-source projects

REPO_ROOT="/Users/i560383_1/code/experiments/test-order"
RESULTS_DIR="${REPO_ROOT}/phase-5-results"
mkdir -p "${RESULTS_DIR}"

# Test configuration
declare -A PROJECTS=(
    ["spring-petclinic"]="Spring PetClinic - Spring Boot web app"
    ["picocli"]="Picocli - Command-line parsing library"
    ["langchain4j"]="LangChain4j - LLM framework"
)

echo "=========================================="
echo "PHASE 5: Real-World Project Testing"
echo "=========================================="
echo ""

for project in "${!PROJECTS[@]}"; do
    desc="${PROJECTS[$project]}"
    echo "Testing: $project"
    echo "Description: $desc"
    echo "---"
    
    project_dir="${REPO_ROOT}/${project}"
    if [ ! -d "$project_dir" ]; then
        echo "ERROR: Project directory not found: $project_dir"
        echo ""
        continue
    fi
    
    # Count tests
    test_count=$(find "$project_dir" -path "*/src/test/*Test.java" -o -path "*/src/test/*Tests.java" 2>/dev/null | wc -l)
    echo "Total test files: $test_count"
    
    # Run Maven test
    echo "Running tests..."
    cd "$project_dir"
    
    start_time=$(date +%s)
    output=$(timeout 300 mvn test 2>&1)
    exit_code=$?
    end_time=$(date +%s)
    elapsed=$((end_time - start_time))
    
    # Extract test results
    tests_run=$(echo "$output" | grep "Tests run:" | tail -1 | grep -o "Tests run: [0-9]*" | grep -o "[0-9]*")
    failures=$(echo "$output" | grep "Tests run:" | tail -1 | grep -o "Failures: [0-9]*" | grep -o "[0-9]*")
    errors=$(echo "$output" | grep "Tests run:" | tail -1 | grep -o "Errors: [0-9]*" | grep -o "[0-9]*")
    skipped=$(echo "$output" | grep "Tests run:" | tail -1 | grep -o "Skipped: [0-9]*" | grep -o "[0-9]*")
    
    echo "Results:"
    echo "  Tests run: ${tests_run:-N/A}"
    echo "  Failures: ${failures:-0}"
    echo "  Errors: ${errors:-0}"
    echo "  Skipped: ${skipped:-0}"
    echo "  Time: ${elapsed}s"
    echo "  Exit code: $exit_code"
    
    # Save results
    {
        echo "# $project"
        echo ""
        echo "## Description"
        echo "$desc"
        echo ""
        echo "## Test Results"
        echo "- Tests run: ${tests_run:-N/A}"
        echo "- Failures: ${failures:-0}"
        echo "- Errors: ${errors:-0}"
        echo "- Skipped: ${skipped:-0}"
        echo "- Execution time: ${elapsed}s"
        echo "- Exit code: $exit_code"
        echo ""
        echo "## Test Output"
        echo "\`\`\`"
        echo "$output" | tail -100
        echo "\`\`\`"
    } > "${RESULTS_DIR}/${project}-results.md"
    
    echo "Results saved to: ${RESULTS_DIR}/${project}-results.md"
    echo ""
done

echo "=========================================="
echo "Phase 5 Testing Complete"
echo "=========================================="
