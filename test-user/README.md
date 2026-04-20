# test-order Plugin - User Testing Setup Guide

## Quick Start

This is a ready-to-use Maven project configured with the test-order plugin for reproducing issues.

### Initial Setup

```bash
cd /Users/i560383_1/code/experiments/test-order/test-user

# Clean everything and rebuild
mvn clean test -Dtestorder.mode=learn
```

This creates the initial dependency index (`test-dependencies.lz4`).

## What's Included

### Application Classes (src/main/java/com/example/)

- `Calculator.java` - Basic math operations
- `StringProcessor.java` - String utilities  
- `DatabaseHelper.java` - In-memory key-value store

### Test Classes (src/test/java/com/example/test/)

- `FastTest.java` - 3 super-fast tests (~2ms each)
- `SlowTest.java` - 3 slow tests (~2700ms each)
- `CalculatorTest.java` - Tests Calculator class
- `StringProcessorTest.java` - Tests StringProcessor
- `DatabaseHelperTest.java` - Tests DatabaseHelper
- `IntegrationTest.java` - Tests all 3 classes together
- `FailingTest.java` - Has one deliberately failing test
- `TestOrderBugTests.java` - Documents specific bugs with steps
- `BugReproductionTests.java` - Detailed bug reproduction with Java test cases

## Common Commands

### Basic Order Mode
```bash
mvn test
# Shows tests in order based on dependencies and changes
```

### Inspect the Order (without running tests)
```bash
mvn test-order:show-order
# Displays computed test order and scores
```

### See Raw Dependency Data
```bash
mvn test-order:dump
# Shows which classes each test exercises
```

### Test with Code Changes (Bug #1)
```bash
# Modify a file to trigger change detection
echo "// Modified" >> src/main/java/com/example/Calculator.java

# Check ordering
mvn test-order:show-order
# First run shows changes, second run doesn't (BUG!)
```

### Test Failure Tracking (Bug #2)
```bash
# Create/edit src/test/java/com/example/test/FailingTest.java

mvn test  
# This will have 1 failure

mvn test-order:show-order
# Check if FailingTest is prioritized (it won't be - BUG!)
```

### Test Select Mode
```bash
# Select and run top 2 tests only
mvn test-order:select test -Dtestorder.select.topN=2 -Dtestorder.select.randomM=0

# Run the remaining tests
mvn test-order:run-remaining test
```

### Test Custom Weights
```bash
# Create custom weights
cat > custom-weights.txt << 'EOF'
newTest = 30
changedTest = 20
maxFailure = 5
speed = 3
speedPenalty = 2
depOverlap = 10
EOF

# Use them
mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt
```

### Explicit Change Detection
```bash
mvn test-order:show-order -Dtestorder.changeMode=explicit -Dtestorder.changedClasses=com.example.Calculator
```

## Files Created During Testing

- `test-dependencies.lz4` - Binary dependency index
- `.test-order-hashes.lz4` - Source file hash snapshot
- `.test-order-test-hashes.lz4` - Test file hash snapshot
- `.test-order-state` - State file with failure history (if failures tracked)
- `target/test-order-selected.txt` - Selected tests (from select mode)
- `target/test-order-remaining.txt` - Remaining tests (from select mode)
- `mvn.out` - Maven output (optional, for debugging)

## Debugging Issues

### Reset Everything
```bash
rm -rf target .test-order* test-dependencies.lz4 *.txt mvn.out
mvn clean test -Dtestorder.mode=learn
```

### See Full Maven Output
```bash
mvn test-order:show-order 2>&1 | tee full-output.txt
```

### Check Test Execution Order
```bash
mvn test 2>&1 | grep "Running com.example" | head -10
```

## Known Bugs (See BUG_REPORT.md)

1. **Negative scores** - Unrelated tests get -1 score
2. **Change detection fails** - Only works once, then stops
3. **No failure tracking** - Failed tests not prioritized
4. **Speed penalties wrong** - All mediums get -1 unnecessarily

## Test Scenarios by Bug

### To reproduce Bug #1 (Negative Scores)
See: `TestOrderBugTests.java::bug_001_negative_scores_when_unrelated()`

Steps in file, or:
```bash
mvn clean test -Dtestorder.mode=learn
echo "// Modified" >> src/main/java/com/example/Calculator.java
mvn test-order:show-order  # Shows changes, scores correct
mvn test-order:show-order  # Shows negative scores for unrelated tests!
```

### To reproduce Bug #2 (Failures Not Tracked)  
See: `BugReproductionTests.java::bug_003_failure_bonus_not_applied()`

Steps in file, or:
```bash
mvn clean test -Dtestorder.mode=learn
# Edit FailingTest.java to have failing test
mvn test  # Will fail
mvn test-order:show-order  # FailingTest not prioritized!
```

### To reproduce Bug #3 (Change Detection Broken)
See: `BugReproductionTests.java::bug_002_change_detection_stops_after_show_order()`

Steps in file, or:
```bash
mvn clean test -Dtestorder.mode=learn
echo "// Modified" >> src/main/java/com/example/Calculator.java
mvn test-order:show-order  # See "Changed classes"
mvn test-order:show-order  # No "Changed classes" shown!
```

## Project Structure

```
test-user/
├── pom.xml                          # Maven configuration with test-order plugin
├── custom-weights.txt               # Example custom weights file
├── BUG_REPORT.md                    # Detailed bug report
├── README.md                         # This file
├── src/main/java/com/example/       # Application code
│   ├── Calculator.java
│   ├── StringProcessor.java
│   └── DatabaseHelper.java
├── src/test/java/com/example/test/  # Test code
│   ├── CalculatorTest.java
│   ├── StringProcessorTest.java
│   ├── DatabaseHelperTest.java
│   ├── IntegrationTest.java
│   ├── FastTest.java
│   ├── SlowTest.java
│   ├── FailingTest.java
│   ├── TestOrderBugTests.java
│   └── BugReproductionTests.java
└── target/                          # Build output
    ├── classes/
    ├── test-classes/
    └── test-order-*.txt            # Selection output
```

## Tips for Testing

1. **Always start fresh** - Run `mvn clean` before testing a scenario
2. **One bug at a time** - Test one scenario, then clean and test the next
3. **Check output carefully** - The show-order table is the key debugging output
4. **Look at durations** - The Duration column shows how test speeds vary
5. **Run multiple times** - Some bugs only show up on subsequent runs

## Next Steps for Developers

1. Read `BUG_REPORT.md` for detailed analysis
2. Review the test methods in `BugReproductionTests.java`
3. Run the reproduction commands above
4. Fix the bugs using this project to verify fixes work

---

**Commands Quick Reference:**

| Task | Command |
|------|---------|
| Setup | `mvn clean test -Dtestorder.mode=learn` |
| Run tests | `mvn test` |
| Check order | `mvn test-order:show-order` |
| See dependencies | `mvn test-order:dump` |
| Select subset | `mvn test-order:select test -Dtestorder.select.topN=2` |
| Custom weights | `mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt` |
| Reset all | `rm -rf target .test-order* test-dependencies.lz4 *.txt mvn.out` |

