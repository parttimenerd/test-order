#!/bin/bash

mkdir -p results

echo "=========================================="
echo "PHASE 5: Windows & Legacy Testing"
echo "=========================================="
echo ""

# Function to test a project
test_project() {
    local project=$1
    local name=$2
    
    echo "Testing: $name"
    cd "$project"
    mvn clean test 2>&1 | tee "../results/${project}-output.log"
    
    # Capture test results
    if [ -d "target/surefire-reports" ]; then
        echo "Tests completed for $name"
    else
        echo "No test reports found for $name"
    fi
    
    cd ..
    echo ""
}

# Run all test projects
test_project "test-project-junit3" "Legacy JUnit 3.x"
test_project "test-project-junit4-old" "Legacy JUnit 4.0-4.8"
test_project "test-project-mixed-junit" "Mixed JUnit 3 and 4"
test_project "test-project-edge-names" "Edge Case Class Names"
test_project "test-project-windows-edge" "Windows Edge Cases"

echo "=========================================="
echo "Test Run Complete"
echo "=========================================="

