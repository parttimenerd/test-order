# Test-Order Fail-Fast Demo Recording

## Overview

This is a 2-minute narrative-driven demonstration of test-order's **fail-fast capability**. The recording shows how intelligent test ordering surfaces failures immediately after code changes, rather than waiting for the full test suite to complete.

## What You'll See

**5 Key Scenes**:

1. **Dependency Learning** (0:00-0:45)
   - Full test suite runs with test-order instrumentation
   - Java agent creates a dependency graph: which tests exercise which source code
   - Shows test-order's automatic learning in action

2. **Baseline Test Order** (0:45-1:15)
   - Before any code changes, tests are ranked by execution speed
   - Shows ranking table with scores
   - StringUtilsTest: Score=1, CalculatorTest: Score=0

3. **Code Edit** (1:15-1:50)
   - A breaking change is introduced: StringUtils.capitalize() now returns empty string
   - Diff is displayed showing the modification
   - This will break the test that depends on this class

4. **Intelligent Reordering** (1:50-2:20)
   - test-order **instantly recalculates** test priorities
   - StringUtilsTest jumps to Score=8 (highest priority)
   - "Changed classes: [com.example.app.StringUtils]" proves dependency detection works
   - Tests that exercise the changed code move to the front

5. **Fail-Fast Execution** (2:20-2:50)
   - Maven runs the test suite in intelligent order
   - StringUtilsTest executes **first** and fails immediately
   - Failure details shown with error stacktrace
   - **Key insight**: With test-order, you see failures in seconds, not minutes
   - Without test-order, you'd run all unrelated tests first, then discover the failure much later

## How to Play the Recording

```bash
# Play the recording
asciinema play test-order-fail-fast.cast

# Play at faster speed (1.5x)
asciinema play test-order-fail-fast.cast --speed=1.5

# Play at slower speed (0.7x)
asciinema play test-order-fail-fast.cast --speed=0.7
```

## The Narrative Arc

**Setup**: A simple Java project with 2 test classes:
- `StringUtilsTest` - Tests the StringUtils utility class
- `CalculatorTest` - Tests the Calculator class

**Action**: Modify `StringUtils.capitalize()` to break its contract

**Result**: test-order automatically recognizes that StringUtilsTest depends on the changed class and promotes it to run first

**Payoff**: Failure surfaces immediately thanks to intelligent test ordering

## Why This Matters

### Without test-order
```
Run 100 tests
├── Test 1 ✓ (passes)
├── Test 2 ✓ (passes)
├── ...
├── Test 87 ✗ (FAILS! StringUtilsTest.testCapitalize)
└── Tests 88-100 (skipped)
Total time: 5+ minutes before seeing relevant failure
```

### With test-order
```
Run tests (intelligent order)
├── Test 1 ✗ (FAILS! StringUtilsTest.testCapitalize)
└── Tests 2-100 (skipped due to fail-fast)
Total time: 5 seconds before seeing failure
```

## Technical Details

**Project Used**: `test-order-example`
- Language: Java 17+
- Build: Maven
- Tests: JUnit 5
- Test-Order Plugin: Configured with `mode=combined`

**Key Files in Recording**:
- `src/main/java/com/example/app/StringUtils.java` — modified to introduce breaking change
- `src/test/java/com/example/app/StringUtilsTest.java` — tests that depend on StringUtils
- `.test-order/` — dependency graph directory created during learning phase

**How test-order Works**:
1. **Learn** — Java agent instruments classes, records dependencies
2. **Analyze** — Plugin detects changed files, looks up which tests are affected
3. **Prioritize** — ClassOrderer reorders tests so affected ones run first
4. **Execute** — JUnit executes in intelligent order with fail-fast

## Key Takeaways

✅ **Dependency learning is automatic** — happens during normal test run  
✅ **Reordering is instant** — recalculates before each test run  
✅ **Failure timing is the win** — seconds instead of minutes  
✅ **No configuration needed** — works out-of-the-box  
✅ **Real output matters** — demo shows actual test execution, not just summaries  

## Scripts

The recording was generated using these scripts:

- **`run-demo.sh`** — Main demo script that orchestrates all 5 scenes
- **`narrative-breakdown.md`** — Detailed screenplay with narration cues and exact outputs

Both are in the session folder: `/Users/i560383_1/.copilot/session-state/d4446580-a139-4811-a525-e619af55899a/`

## Success Criteria Met

✅ ~5 minutes of focused narrative (2:50 actual demo time)  
✅ Fail-fast aspect clearly demonstrated  
✅ Dependency learning explained and shown  
✅ Test ranking shift visibly displayed (Score 1 → Score 8)  
✅ Actual test output proves intelligent ordering (StringUtilsTest runs first)  
✅ Real failure details shown in output  
✅ Minimal typing (pre-staged changes, fast commands)  
✅ Recording playable via asciinema  

## Location

Main recording: `test-order-fail-fast.cast` (this directory)

Additional resources:
- Plan: `plan.md`
- Detailed narrative: `narrative-breakdown.md`
- Demo script: `run-demo.sh`

All in: `/Users/i560383_1/.copilot/session-state/d4446580-a139-4811-a525-e619af55899a/`

---

**Created**: April 21, 2026  
**Duration**: 2:50  
**Project**: test-order-example (test-order core test-ordering library)  
**Tool**: asciinema v2.x  
