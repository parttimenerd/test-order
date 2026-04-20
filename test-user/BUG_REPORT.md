# test-order Plugin - User Acceptance Testing Report

**Date**: April 15, 2026  
**Tester**: Using plugin as end user without reading source code  
**Test Duration**: ~60 minutes  
**Test Coverage**: Learn mode, Order mode, Auto mode, Show-order, Dump, Select mode, Combined mode, Weights customization

## Executive Summary

The test-order plugin works for basic scenarios (learn mode + order mode with detected changes). However, several critical bugs prevent it from being reliable for production use:

1. **Negative scores** assigned to unrelated tests (HIGH)
2. **Failed test detection not working** (HIGH)  
3. **Change detection breaks after diagnostic commands** (MEDIUM)
4. **Incorrect speed bonus/penalty calculation** (MEDIUM)

---

## Bug #1: Negative Scores for Unrelated Tests

**Severity**: HIGH  
**Impact**: Scoring system fails, tests are not properly prioritized  

### Reproduction Steps

```bash
cd /Users/i560383_1/code/experiments/test-order/test-user

# Step 1: Create dependency index
mvn clean test -Dtestorder.mode=learn

# Step 2: Check baseline ordering (all scores = 0)
mvn test-order:show-order

# Step 3: Modify an application class
# Edit: src/main/java/com/example/Calculator.java
# Add comment: // Modified by test user

# Step 4: Check ordering with changes
mvn test-order:show-order
```

### Expected Behavior

```
Changed classes: [com.example.Calculator]

  #    Test Class                    Score  Deps  Fail  Changed Duration
  1.   c.e.test.CalculatorTest          12     1            yes    
  2.   c.e.test.IntegrationTest          2     1                 
  3.   c.e.test.DatabaseHelperTest       0                        
  4.   c.e.test.FastTest                 0                        
  5.   c.e.test.StringProcessorTest      0                        
  6.   c.e.test.SlowTest                -1                        
```

- CalculatorTest: 12 (changed test bonus +9, dependency overlap +3)
- IntegrationTest: 2 (dependency overlap: 1/3 * 5 = ceil(1.67) = 2)
- Others: 0 or small penalty

### Actual Behavior

After running show-order a second time:
```
  #    Test Class                    Score  Deps  Fail  Changed Duration
  1.   c.e.test.DatabaseHelperTest       0                           2ms
  2.   c.e.test.FastTest                 0                           2ms
  3.   c.e.test.StringProcessorTest      0                           2ms
  4.   c.e.test.IntegrationTest         -1                           4ms
  5.   c.e.test.CalculatorTest          -1                          42ms
  6.   c.e.test.SlowTest                -1                        2709ms
```

- All tests except FastTest variants get score -1
- CalculatorTest loses its changed test bonus
- Unrelated tests are penalized

### Root Cause Hypothesis

1. Speed penalty is being applied to ALL tests when there's variance in test duration
2. The penalty -1 appears to be coming from speed penalty calculation
3. Tests with durations between fast (25%) and slow (75%) percentiles are getting -1 penalty for no clear reason

### Test Case

See `BugReproductionTests.java::bug_001_negative_scores_when_unrelated()`

---

## Bug #2: Failure Bonus Not Applied / Failures Not Tracked

**Severity**: HIGH  
**Impact**: Core feature not working - failed tests are not prioritized  

### Reproduction Steps

```bash
cd /Users/i560383_1/code/experiments/test-order/test-user

# Create FailingTest.java with deliberately failing test
cat > src/test/java/com/example/test/FailingTest.java << 'EOF'
package com.example.test;
import com.example.Calculator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class FailingTest {
    private Calculator calc = new Calculator();
    @Test public void testAddWrong() { assertThat(calc.add(2,3)).isEqualTo(10); }
    @Test public void testSubtract() { assertThat(calc.subtract(5,3)).isEqualTo(2); }
}
EOF

# Run tests (1 will fail)
mvn test

# Check scoring
mvn test-order:show-order
```

### Expected Behavior

After test failure, `show-order` output should show:

```
  #    Test Class                    Score  Deps  Fail  Changed Duration
  1.   c.e.test.FailingTest             15     1    1            33ms
  2.   c.e.test.CalculatorTest           0     1                  
  ...
```

- FailingTest: score 15 (new test bonus 15, failure bonus added)
- "Fail" column shows 1 (indicating 1 recent failure)
- FailingTest runs first (highest score)

### Actual Behavior

```
  #    Test Class                    Score  Deps  Fail  Changed Duration
  1.   c.e.test.TestOrderBugTests       14                          11ms
  2.   c.e.test.FailingTest             14                          33ms
  3.   ...
```

- FailingTest score = CalculatorTest score (no failure bonus)
- "Fail" column is empty
- Failures are not reflected in scoring
- FailingTest is not prioritized higher than other new tests

### Root Cause Hypothesis

The `.test-order-state` state file (which persists failure history) is not being:
1. Written with failure information during test runs
2. Read and loaded when computing scores in order mode

### Test Case

See `BugReproductionTests.java::bug_003_failure_bonus_not_applied()`

---

## Bug #3: Change Detection Stops After Diagnostic Commands

**Severity**: MEDIUM  
**Impact**: Users think their changes are no longer detected  

### Reproduction Steps

```bash
cd /Users/i560383_1/code/experiments/test-order/test-user

mvn clean test -Dtestorder.mode=learn

# Modify a file
echo "// Changed" >> src/main/java/com/example/Calculator.java

# First check - changes detected
mvn test-order:show-order
# Output shows: "Changed classes: [com.example.Calculator]" ✓

# Second check - changes not detected
mvn test-order:show-order
# Output shows: No "Changed classes:" line  ✗
```

### Expected Behavior

The `show-order` command should NOT modify hash snapshots. Running it multiple times should consistently report changed files.

### Actual Behavior

- First `show-order`: Reports changed classes correctly
- Second `show-order`: No changed classes reported
- The `.test-order-hashes.lz4` file is modified by show-order command

### Root Cause Hypothesis

The `show-order` command (or the underlying ordering logic) is updating the `.test-order-hashes.lz4` snapshot file, causing subsequent runs to not detect files as changed.

**Fix required**: Either:
- Don't modify hash snapshots in diagnostic/order modes; only in learn mode
- Or regenerate snapshots with current file state before comparison

### Test Case

See `BugReproductionTests.java::bug_002_change_detection_stops_after_show_order()`

---

## Bug #4: Speed Bonus/Penalty Calculation or Display Incorrect

**Severity**: MEDIUM  
**Impact**: Tests are not ordered optimally by speed  

### Reproduction Steps

```bash
cd /Users/i560383_1/code/experiments/test-order/test-user

# Create custom weights emphasizing speed
cat > custom-weights.txt << 'EOF'
newTest = 30
changedTest = 20
maxFailure = 5
speed = 3
speedPenalty = 2
depOverlap = 10
EOF

# With test suite having:
# - FastTest: 3 tests × ~2ms each
# - SlowTest: 3 tests × ~2700ms each

mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt
```

### Expected Output (with speedPenalty=2)

```
  #    Test Class                    Score  Duration
  1.   c.e.test.FastTest                 3          2ms
  2.   c.e.test.StringProcessorTest      3          2ms
  3.   c.e.test.IntegrationTest          1          4ms
  4.   c.e.test.CalculatorTest           1          2ms
  5.   c.e.test.DatabaseHelperTest       1          4ms
  6.   c.e.test.SlowTest                -2       2709ms
```

Fast tests (<338ms threshold) get +3 speed bonus.  
Slow tests (>1016ms threshold) get -2 speed penalty.  
Medium tests get neither bonus nor penalty.

### Actual Output

```
  #    Test Class                    Score  Duration
  1.   c.e.test.DatabaseHelperTest       0          2ms
  2.   c.e.test.FastTest                 0          2ms
  3.   c.e.test.StringProcessorTest      0          2ms
  4.   c.e.test.IntegrationTest         -2          4ms
  5.   c.e.test.CalculatorTest          -2         42ms
  6.   c.e.test.SlowTest                -2       2709ms
```

- Speed bonus (3) is not being applied to fast tests  
- Speed penalty is applied to almost all tests
- Non-fast tests (4ms, 42ms) incorrectly receive -2 penalty

### Root Cause Hypothesis

1. Speed bonus might not be implemented or is being overridden by something else
2. Speed penalty thresholds are calculated incorrectly
3. All tests receiving same penalty suggests a global calculation error

### Test Case

See `BugReproductionTests.java::bug_005_speed_bonus_penalty_calculation()`

---

## Bug #5 (Minor): Dependency Display Confusing

**Severity**: LOW (Display issue)  

The "Deps" column shows the number of changed classes overlapping, not total dependencies. This is confusing because:
- IntegrationTest depends on 3 classes: Calculator, StringProcessor, DatabaseHelper
- With 1 changed (Calculator), it shows "Deps: 1"
- But a user would expect "Deps: 3" (total it depends on)

Recommend renaming column to "Overlap" or "Changed Deps".

---

## Summary: What Works ✓ and What Doesn't ✗

### Working Features ✓

- ✓ Learn mode: Correctly instruments and records test class dependencies
- ✓ Dump command: Shows dependency information clearly
- ✓ Order mode with explicit changes: Can prioritize when manually specified
- ✓ Select/Run-remaining modes: Partition and run tests correctly
- ✓ Combined mode: Orchestrates the workflow
- ✓ Weights file loading: Custom weights are applied

### Broken Features ✗

- ✗ Change detection after show-order: Hash snapshots incorrectly updated
- ✗ Automatic score calculation: Results in negative scores
- ✗ Failure tracking: Failures not persisted or loaded
- ✗ Speed bonus: Not applied, penalties applied to all mediums
- ✗ Changed test bonus: Lost after first diagnostic command

---

## Test Project Location

All reproduction happens in this project structure:

```
/Users/i560383_1/code/experiments/test-order/test-user/
├── pom.xml
├── src/main/java/com/example/
│   ├── Calculator.java
│   ├── StringProcessor.java
│   └── DatabaseHelper.java
├── src/test/java/com/example/test/
│   ├── CalculatorTest.java
│   ├── StringProcessorTest.java
│   ├── DatabaseHelperTest.java
│   ├── IntegrationTest.java
│   ├── FastTest.java
│   ├── SlowTest.java
│   ├── FailingTest.java
│   ├── TestOrderBugTests.java
│   └── BugReproductionTests.java (this file contains Java reproduction code)
└── test-dependencies.lz4 (created by learn mode)
```

Test files contain inline documentation with exact reproduction steps.

---

## Recommendations for Fixes

### Priority 1 (Blocking)

1. **Fix failure tracking**
   - Ensure failures are written to `.test-order-state`
   - Load and apply failure bonus during score calculation
   - Add proper visibility in show-order output

2. **Fix hash snapshot management**
   - Don't update snapshots in order/show-order modes
   - Only update snapshots in learn mode
   - Consider reading fresh hashes but not persisting them in diagnostic commands

3. **Fix score calculation**
   - Debug why negative scores appear for unrelated tests
   - Ensure changed test bonus (+9) is always applied for changed tests
   - Fix speed penalty application (should only be for slow tests, not all tests)

### Priority 2 (Important)

4. **Fix speed bonus**
   - Implement speed bonus for fast tests (<25% percentile)
   - Verify penalty thresholds and formula

5. **Improve documentation**
   - Clarify "Deps" column meaning
   - Add examples of expected scoring output

---

## Quick Reference: Bug Severity

| Bug | Impact | Test Case |
|-----|--------|-----------|
| #1: Negative scores | Failed prioritization | `BugReproductionTests::bug_001_*` |
| #2: No failure tracking | Can't detect regressions | `BugReproductionTests::bug_003_*` |
| #3: Change detection fails | False negatives | `BugReproductionTests::bug_002_*` |
| #4: Speed calculation wrong | Suboptimal ordering | `BugReproductionTests::bug_005_*` |
| #5: Display confusing | UX issue | N/A |

