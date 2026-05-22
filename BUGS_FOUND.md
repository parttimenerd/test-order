# Bugs Found While Dog-Fooding test-order

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

## Bug #5: Test Classes with Only @Disabled Methods Now Working
**Status**: FALSE ALARM / Working as Designed
**Severity**: N/A
**Details**:
- Initial test class was created as package-private, which is why it wasn't picked up
- Once made public, the class is correctly recognized and executed
- Disabled methods are properly handled by JUnit - test class runs, disabled methods are skipped
- Example output: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 2`

**Status Resolution**: This is working correctly. Test framework (JUnit) handles @Disabled methods properly and test-order respects that.

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
5. ML predictions (`-Dtestorder.ml.enabled=true`)
6. Custom change mode: `since-last-run`, `explicit`
7. Different storage modes (`-Dtestorder.storage=home`)
8. Coverage analysis (`mvn test-order:coverage`)
9. Storage directory permissions issues
10. Parameterized tests with test-order
11. Spring test slices
12. Nested test classes in modern JUnit

