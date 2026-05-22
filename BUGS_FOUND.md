# Bugs Found While Dog-Fooding test-order

## Quick Summary
- **5 Confirmed Real Bugs** needing fixes
- **1 Missing Feature** (documented but not implemented)  
- **3 False Alarms** (working as designed)

### Real Bugs by Severity:
1. **Bug #6** (High): Method reordering breaks order-dependent tests
2. **Bug #4** (High): JUnit 4 Vintage engine ClassNotFoundException  
3. **Bug #8** (High): Nested test classes not executed
4. **Bug #7** (Medium): Invalid weights file silently ignored
5. **Bug #9** (Medium): @Order annotation requires @TestMethodOrder to be respected
6. **Bug #1** (Low): Missing time estimate in APFD message

---

## Bug #1: Missing Time Estimate in APFD Message
**Status**: CONFIRMED - Feature Not Implemented
**Severity**: Low (cosmetic)
**Details**: 
- When a test failure is detected, the tool prints the APFD score and position of first failure
- Example: `[test-order] Run APFD: 78.6% (first failure at position 2/7)`
- README claims feature should also show: `⏱️  Estimated time saved: 21s (based on default execution order)`
- This time estimate feature is not implemented in the code
- **Reproduction**: 
  1. Create a failing test
  2. Run `mvn test`
  3. Check console for APFD message - time saved is never printed

**Expected**: Both APFD and time savings should be printed
**Actual**: Only APFD is printed, time savings are missing

**Code Location**: `test-order-junit/src/main/java/me/bechberger/testorder/junit/TelemetryListener.java` around the APFD logging

**Note**: This is mentioned in the README at line 305-306, but the implementation is missing. To implement this:
1. Track cumulative execution time of each test (already available)
2. Calculate time of tests that would have run before first failure in default (alphabetical) order
3. Subtract from actual time to get savings estimate
4. Print alongside APFD message

---

## Bug #2: `test-order:run-remaining` Works But Was Not Tested Properly  
**Status**: FALSE ALARM / Working as Designed
**Severity**: N/A
**Details**:
- Initial test was inconclusive: ran select without changes or with topN=-1 (default)
- When topN=-1 (default), all affected tests are selected, leaving no remaining tests
- Remaining file IS created when topN < number of affected tests
- **Actual behavior**: 
  - `mvn test-order:select test -Dtestorder.select.topN=3` creates remaining file with unselected tests
  - `mvn test-order:run-remaining test` correctly reads and executes remaining tests
  - Remaining file is moved to `.consumed` after execution (for recovery if needed)

**Status Resolution**: This is working correctly. The confusion arose from testing with default parameters that selected all tests.

---

## Bug #3: Fallback Collector File Warning in Multi-Module Builds
**Status**: NOT A BUG - Expected Behavior
**Severity**: N/A
**Details**:
- Message appears: `[test-order] IndexCollectorServer: merge failed in shutdown hook (NoClassDefFoundError: org/roaringbitmap/IntConsumer)`
- This is expected when IndexCollectorServer attempts to finalize but classes are already unloaded
- A fallback file is written to allow recovery: `test-dependencies.lz4.collector-fallback`
- Tests still complete successfully and results are correct
- The fallback is a safety mechanism to prevent data loss on merge failures

**Status Resolution**: This is working as designed. The fallback mechanism ensures data is not lost even if normal finalization fails.

---

## Bug #4: JUnit 4 / Vintage Engine ClassNotFoundException for MethodOrderer
**Status**: CONFIRMED - Real Bug
**Severity**: High (runtime exception)
**Details**:
- When running JUnit 4 tests via the Vintage engine, `TelemetryListener` throws exception during test plan finalization
- Error: `java.lang.NoClassDefFoundError: org/junit/jupiter/api/MethodOrderer`
- Tests still execute and pass, but the error appears in output and might cause issues in CI
- Root cause: TelemetryListener references JUnit 5 API (`MethodOrderer`) which isn't available in JUnit 4 runtime
- **Reproduction**: Run `samples/sample-vintage` with JUnit 4 on the Vintage engine

**Expected**: No exceptions during test execution or finalization
**Actual**: ClassNotFoundException in TelemetryListener at testPlanExecutionFinished

**Code Location**: The TelemetryListener is likely importing or using MethodOrderer unconditionally

---

## Bug #6: Method-Level Reordering Can Break Order-Dependent Tests
**Status**: CONFIRMED - Real Bug
**Severity**: High (breaks order-dependent tests)
**Details**:
- When method-level ordering is enabled or when test methods are reordered, tests with implicit order dependencies fail
- Example: Test class with `testSetCounter()` and `testCheckCounter()` where the latter expects the former to run first
- With random reordering, `testCheckCounter()` runs first and fails because `counter` is 0 instead of expected 42
- Even when test class uses static shared state, test-order will reorder methods and break the dependency
- **Reproduction**:
  1. Create SharedStateTest with two methods that depend on execution order
  2. Run `mvn test -Dtestorder.methodOrder.enabled=true`
  3. Test fails because order is randomized

**Expected**: Tests with order dependencies should either:
- Be detected and run in dependency order, OR
- Be flagged as requiring explicit @Order annotation

**Actual**: Tests are randomly reordered and fail silently on first run, detection doesn't catch same-class dependencies

**Related**: detect-dependencies fails to find order-dependent tests within the same class

---

## Bug #7: Invalid or Missing Weights File Silently Ignored
**Status**: CONFIRMED - Real Bug  
**Severity**: Medium (silent failure)
**Details**:
- When passing `-Dtestorder.weightsFile=/path/to/file.toml`, if the file is:
  - Non-existent (doesn't exist)
  - Invalid TOML syntax  
  - Empty or malformed
- The tool silently ignores it and uses default weights with NO error/warning
- User has no way to know if their custom weights were actually loaded
- **Reproduction**:
  1. `mvn test -Dtestorder.weightsFile=/nonexistent/file.toml` → silently uses defaults
  2. `mvn test -Dtestorder.weightsFile=/tmp/malformed.toml` (with bad TOML) → silently uses defaults

**Expected**: Tool should warn or fail when:
- Weights file path is specified but doesn't exist
- Weights file cannot be parsed
- At minimum, log which weights file was actually loaded

## Bug #8: Nested Test Classes Detected But Not Executed
**Status**: CONFIRMED - Real Bug
**Severity**: High (tests don't run)
**Details**:
- When test classes use @Nested (JUnit 5 feature), test-order detects them as new test classes
- Shows up in logs: `NestedTestClass`, `NestedTestClass$InnerTests`, `NestedTestClass$InnerTests$DeeplyNestedTests`
- However, these nested tests never actually execute - they don't appear in test execution output
- Total test count doesn't match expected (nested tests are missing from count)
- **Reproduction**:
  1. Create a test class with @Nested inner classes
  2. Run `mvn test`
  3. test-order logs show nested classes detected, but they never run

**Expected**: Nested test classes should execute along with outer class tests

**Actual**: Nested test classes are indexed but filtered out of execution, resulting in missing tests

**Impact**: Users won't know their nested tests aren't running

---

## Observations (Not Bugs)

### Good Behavior
1. **New test detection works**: New test classes are correctly identified and prioritized
2. **Parallel execution**: Works with class-level parallelism (Surefire `<parallel>`, threadCount)
3. **Diagnostics**: `mvn test-order:diagnose` provides comprehensive health check
4. **Dashboard**: Generates comprehensive HTML reports
5. **Multi-module support**: Handles multi-module projects with separate indices per module
6. **Framework detection**: Automatically detects JUnit 5, JUnit 4 (Vintage), TestNG
7. **Mode switching**: Correctly switches between learn and order modes
8. **Method ordering**: Works when enabled with `-Dtestorder.methodOrder.enabled=true`
9. **Change detection**: Accurately tracks uncommitted changes and prioritizes affected tests

### Edge Cases Tested
- ✓ Empty projects (no tests) - handled gracefully
- ✓ Project with no source code changes - scores still apply, no change bonus
- ✓ Parallel class execution - respects ordering priority hints
- ✓ Multi-module builds - separate indices maintained
- ✓ Mixed frameworks (JUnit 5 + TestNG) - works correctly
- ✓ Method-level ordering - functions when enabled
- ✓ Always-learn mode - enforces learn mode on every run
- ✓ Dashboard generation - creates valid HTML reports
- ✓ Dependency detection - finds order-dependent tests

---

## Missing Test Scenarios

The following scenarios were NOT tested (due to time constraints or complexity):
1. Gradle integration (only Maven was tested)
2. JaCoCo + test-order coexistence
3. Large-scale projects (1000+ tests)
4. Custom scoring configuration (TOML)
5. Storage directory permissions issues
6. Spring test slices
7. Custom change mode: `since-last-run`

**Previously untested scenarios that have NOW been verified as working:**
- ✓ ML predictions (`-Dtestorder.ml.enabled=true`)
- ✓ Custom change mode: `explicit` and `since-last-commit`
- ✓ Different storage modes (`-Dtestorder.storage=home`)
- ✓ Coverage analysis (`mvn test-order:coverage`)
- ✓ Parameterized tests with `@ParameterizedTest`, `@ValueSource`, `@CsvSource`
- ✓ Method name pattern filtering (`ValidatorTest#test*`)
- ✓ Tests with static initializers
- ✓ Exception handling in tests (thrown RuntimeException properly detected as failure)
- ✓ Skip mode (`-Dtestorder.mode=skip`)
- ✓ Always-learn mode (`-Dtestorder.mode=learn`)

---
## Bug #9: @Order Annotation Without @TestMethodOrder Not Respected
**Status**: CONFIRMED - Design/Documentation Issue
**Severity**: Medium (usability/documentation)
**Details**:
- When using `@Order` annotation alone (without `@TestMethodOrder`), test-order reorders tests anyway
- Developers may expect `@Order` to enforce execution order by itself
- test-order correctly respects ordering when `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` is added
- **Reproduction**:
  1. Create test class with `@Test @Order(1)`, `@Test @Order(2)`, `@Test @Order(3)`
  2. DON'T add `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` to class
  3. Run tests - they execute in random order (test-order reorders them)
  4. Add `@TestMethodOrder` annotation to class
  5. Now ordering is properly respected

**Expected**: `@Order` annotation alone should enforce method execution order

**Actual**: `@TestMethodOrder` must be explicitly specified for order to be respected

**Impact**: Tests with order dependencies may fail silently if only `@Order` is used

**Note**: This is a design/documentation issue rather than a code bug. test-order's behavior (reordering) is correct by design, but it should be documented that `@TestMethodOrder` is required for the annotation to have effect.

---

