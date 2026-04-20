# test-order Plugin - Testing Summary & Bugs Found

## Overview

I tested the test-order plugin end-to-end as a regular user without reading the source code. Created a complete Maven project with 7 test classes across 3 application classes, tested all major features, and documented all issues.

## Test Results Summary

✅ **Working**: 
- Learn mode (dependency index creation)
- Dump command (displaying dependencies)
- Select/Run-remaining modes
- Combined mode orchestration
- Custom weights loading

❌ **Not Working**:
- Failure tracking and bonus scoring
- Change detection (breaks after show-order)
- Score calculation (produces negative scores)
- Speed bonus application

---

## Critical Bugs Found (5 Total)

### 🔴 BUG #1: NEGATIVE SCORES FOR UNRELATED TESTS

**Severity**: HIGH - Core feature broken  
**Type**: Scoring system  

When you have slow and fast tests together, unrelated tests get negative scores:

```
# After modifying Calculator.java:
DatabaseHelperTest:    -1  ❌ (should be 0, not related to changes)
FastTest:               0  ✅ (correct)
StringProcessorTest:   -1  ❌ (should be 0, not related)
CalculatorTest:        -1  ❌ (should be +9 for changed test bonus!)
SlowTest:              -1  ❌ (might need slight penalty, but affects others)
```

**Reproduce**:
```bash
cd /Users/i560383_1/code/experiments/test-order/test-user
mvn clean test -Dtestorder.mode=learn
echo "// Modified" >> src/main/java/com/example/Calculator.java
mvn test-order:show-order  # Run TWICE
# Second run shows negative scores
```

**Java Test Case**: `TestOrderBugTests.java::bug_001_negative_scores_when_unrelated()`

**Issue**: All tests get penalized with -1 score when there's speed variance

---

### 🔴 BUG #2: FAILURES NOT TRACKED / NO FAILURE BONUS

**Severity**: HIGH - Core feature not working  
**Type**: Failure tracking  

Failed tests are not prioritized for re-running:

```
# After FailingTest fails with 1 test failure:
mvn test-order:show-order
# Output shows:
FailingTest:  Score 14  Fail: [empty]  ❌
# EXPECTED: Score 15+, Fail: 1, runs FIRST
# ACTUAL: Same as other new tests, failures not visible
```

**Reproduce**:
```bash
cd /Users/i560383_1/code/experiments/test-order/test-user
# Use existing FailingTest.java with deliberately wrong assertion
mvn test  # Results: 1 failure
mvn test-order:show-order  # FailingTest not prioritized!
```

**Java Test Case**: `BugReproductionTests.java::bug_003_failure_bonus_not_applied()`

**Issue**: The `.test-order-state` file isn't storing or loading failure history properly

---

### 🟠 BUG #3: CHANGE DETECTION BREAKS AFTER SHOW-ORDER

**Severity**: MEDIUM - Affects usability  
**Type**: Hash snapshot management  

Run show-order twice and change detection stops working:

```bash
mvn test-order:show-order
# Output: "Changed classes: [com.example.Calculator]" ✅

mvn test-order:show-order  
# Output: NO "Changed classes:" line shown ❌
# Hash snapshots were updated by the first command
```

**Reproduce**:
```bash
cd /Users/i560383_1/code/experiments/test-order/test-user
mvn clean test -Dtestorder.mode=learn
echo "// Modified" >> src/main/java/com/example/Calculator.java
mvn test-order:show-order  # Shows "Changed classes: [com.example.Calculator]"
mvn test-order:show-order  # No "Changed classes:" shown!
```

**Java Test Case**: `BugReproductionTests.java::bug_002_change_detection_stops_after_show_order()`

**Issue**: Diagnostic commands (.e.g show-order) shouldn't modify hash snapshots

---

### 🟠 BUG #4: SPEED BONUS NOT APPLIED / PENALTY CALCULATION WRONG

**Severity**: MEDIUM - Affects test ordering quality  
**Type**: Speed scoring  

Speed bonus for fast tests is not applied, penalties are inconsistent:

```bash
# With speedPenalty=2 in weights:
FastTest (2ms):                 0  ❌ Should be +3
IntegrationTest (4ms):         -2  ❌ Should be 0
CalculatorTest (42ms):         -2  ❌ Should be 0
SlowTest (2709ms):             -2  ✅ Correct
```

Median ~1355ms, so:
- Fast threshold (25%) = 338ms → 2ms is fast ✓
- Slow threshold (75%) = 1016ms → 2709ms is slow ✓
- But: FastTest doesn't get +3, others get -2 incorrectly

**Reproduce**:
```bash
cd /Users/i560383_1/code/experiments/test-order/test-user
cat > custom-weights.txt << 'EOF'
speed = 3
speedPenalty = 2
EOF
mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt
# FastTest should have +3, instead shows 0
```

**Java Test Case**: `BugReproductionTests.java::bug_005_speed_bonus_penalty_calculation()`

**Issue**: Speed bonus not implemented, penalty calculation has bugs

---

### 🟡 BUG #5: DEPENDENCY DISPLAY CONFUSING (Minor)

**Severity**: LOW - Display/documentation issue  
**Type**: UX  

The "Deps" column is misleading:

```
IntegrationTest depends on 3 classes: Calculator, StringProcessor, DatabaseHelper
With 1 changed (Calculator):
  Show-order displays: Deps=1  ❌ Shows overlap, not total
  User expects: Deps=3 (total dependencies)
```

**Fix**: Rename column to "Overlap" or "Changed" for clarity

---

## Complete Test Scenarios Documented

All bugs have complete Java test cases with code comments explaining:
1. What the bug is
2. Exact reproduction steps
3. Expected vs Actual behavior
4. Root cause hypothesis

**Location**: `/Users/i560383_1/code/experiments/test-order/test-user/`

Files:
- `BugReproductionTests.java` - Detailed reproduction code
- `TestOrderBugTests.java` - Additional test scenarios
- `BUG_REPORT.md` - Full analysis document
- `README.md` - Setup and quick reference

---

## Test Project Details

### What Was Tested

✅ Learn mode - dependency discovery  
✅ Order mode - test reordering with changes  
✅ Auto mode - automatic change detection  
✅ Show-order command - displaying scores  
✅ Dump command - dependency inspection  
✅ Select/run-remaining - subset selection  
✅ Combined mode - full workflow  
✅ Custom weights - score customization  
✅ Explicit change mode - manual specification  
✅ Failure tracking - (not working)  

### Project Structure

```
/Users/i560383_1/code/experiments/test-order/test-user/
├── Application Classes (fast, simple):
│   ├── Calculator (math ops)
│   ├── StringProcessor (string utils)
│   └── DatabaseHelper (in-memory store)
│
├── Test Classes with coverage variety:
│   ├── FastTest (super fast, ~2ms)
│   ├── SlowTest (slow, ~2700ms)
│   ├── CalculatorTest (single dependency)
│   ├── IntegrationTest (3 dependencies)
│   ├── StringProcessorTest (single dependency)
│   ├── DatabaseHelperTest (single dependency)
│   ├── FailingTest (has 1 failing test)
│   └── Multiple bug reproduction classes
│
└── Configuration:
    ├── pom.xml (with test-order plugin configured)
    ├── BUG_REPORT.md (comprehensive analysis)
    └── README.md (setup guide)
```

### Key Metrics

- **Test Classes**: 8 (with bug cases)
- **Test Methods**: 25+
- **Application Classes**: 3
- **Dependency Variety**: 1, 2, and 3-class dependencies
- **Speed Range**: 2ms - 2700ms (1000x variance)
- **Failure Scenarios**: 1 test failing
- **Setup Time**: ~50 minutes to discover, reproduce, and document

---

## How to Use This for Fixing

### For Developer #1 - Focus on Bugs #1 and #2

Start with the scoring system:
1. Read `BUG_REPORT.md` sections on Bugs #1 and #2
2. Look at show-order output format
3. Debug the scoring formula application
4. Run reproduction commands to verify fixes

### For Developer #2 - Focus on Bugs #3 

Hash snapshot management:
1. Read `BUG_REPORT.md` section on Bug #3
2. Investigate when/how `.test-order-hashes.lz4` is updated
3. Ensure show-order doesn't update snapshots
4. Test with reproduction steps

### For Developer #3 - Focus on Bug #4

Speed bonus/penalty:
1. Read `BUG_REPORT.md` section on Bug #4
2. Check speed bonus implementation
3. Verify percentile thresholds (25% and 75%)
4. Use custom-weights file when testing

---

## Quick Command Reference

```bash
# Setup
cd /Users/i560383_1/code/experiments/test-order/test-user
mvn clean test -Dtestorder.mode=learn

# Test ordering
mvn test-order:show-order

# See dependencies
mvn test-order:dump

# Reproduce Bug #1
echo "// Modified" >> src/main/java/com/example/Calculator.java
mvn test-order:show-order  # Two times

# Reproduce Bug #2
mvn test  # Will show 1 failure in FailingTest

# Reproduce Bug #3
mvn test-order:show-order
mvn test-order:show-order  # Run twice

# Reproduce Bug #4
mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt

# Reset
rm -rf target .test-order* test-dependencies.lz4 *.txt
```

---

## What Went Well

The plugin handles the basic workflow correctly:
- ✅ Dependency instrumentation works well
- ✅ Binary format (LZ4) works efficiently
- ✅ Maven integration is seamless
- ✅ The dump output is useful for understanding
- ✅ Select/run-remaining modes are intuitive

---

## What Needs Fixing

The plugin breaks on real-world usage:
- ❌ Scoring formula produces incorrect scores
- ❌ Failures aren't recorded or loaded
- ❌ Change detection isn't stable
- ❌ Speed calculations are wrong
- ❌ Column names are confusing

---

## Next Steps for You

1. **Read the bugs**: Start with `BUG_REPORT.md`
2. **Try reproduction**: Use the commands above  
3. **Run test cases**: Check the Java test files
4. **Share with team**: All docs are self-contained in test-user/
5. **Plan fixes**: Assign developers to each bug priority

The test project is ready to use for verification once bugs are fixed!

