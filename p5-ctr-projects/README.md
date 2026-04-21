# Phase 5 CTR Test Projects - Master Index

## Quick Start: Running the Test Projects

### All Projects at Once
```bash
cd /Users/i560383_1/code/experiments/test-order/p5-ctr-projects

# Build all projects
for project in custom-listeners junit4-custom-runner mixed-junit-versions annotation-edge-cases kotest-advanced; do
  echo "Building $project..."
  (cd $project && mvn clean test)
done
```

### Individual Projects

#### 1. custom-listeners (JUnit 5 - RECOMMENDED STARTING POINT)
```bash
cd custom-listeners
mvn clean test

# Expected: 33 tests pass, custom listener tracks execution timing
# Output: Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

**What it tests:**
- Custom JUnit 5 TestExecutionListener via service loader
- @ParameterizedTest with @ValueSource (ints)
- @ParameterizedTest with @ValueSource (strings)
- @ParameterizedTest with @CsvSource
- @ParameterizedTest with @MethodSource
- Test inheritance (base class with inherited tests)
- @Nested inner classes with doubly nested structure
- Custom annotations and test lifecycle

**Test Classes:**
- `TimingListener.java` - Custom test execution listener
- `ParameterizedValueTest.java` - Tests with value-based parameters
- `ParameterizedCsvTest.java` - Tests with CSV parameters
- `ParameterizedMethodSourceTest.java` - Tests with method-supplied parameters
- `InheritanceBaseTest.java` - Base test class
- `InheritanceChildTest.java` - Child extending base
- `NestedInnerClassTest.java` - Complex nested structure

---

#### 2. junit4-custom-runner (JUnit 4)
```bash
cd junit4-custom-runner
mvn clean test

# Expected: 11 tests pass
# Output: Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

**What it tests:**
- JUnit 4 @RunWith(Parameterized.class) custom runner
- Parameter combinations (4 parameters × 2 test methods = 8 tests)
- @Before/@After lifecycle hooks
- Standard @Test annotations

**Test Classes:**
- `JUnit4ParameterizedTest.java` - 4 parameters, 2 test methods each
- `JUnit4SimpleTest.java` - Standard JUnit 4 tests with lifecycle hooks

---

#### 3. mixed-junit-versions (JUnit 4 + JUnit 5)
```bash
cd mixed-junit-versions
mvn clean test

# Expected: 4 tests pass (both frameworks together)
# Output: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

**What it tests:**
- JUnit 4 test classes alongside JUnit 5
- Both frameworks running in same Maven build
- Surefire provider auto-selection
- JUnit Vintage engine for JUnit 4 compatibility

**Test Classes:**
- `JUnit4LegacyTest.java` - 2 JUnit 4 tests
- `JUnit5ModernTest.java` - 2 JUnit 5 tests

---

#### 4. annotation-edge-cases (JUnit 5 - INCLUDES BUG REPRODUCER)
```bash
cd annotation-edge-cases
mvn clean test

# Expected: 4 tests pass, 2 skip, 3 errors
# Output: Tests run: 8, Failures: 0, Errors: 3, Skipped: 2, <<< FAILURE!
# ⚠️  Bug: @RepeatedTest fails due to parameter injection issue
```

**What it tests:**
- @Disabled annotation handling
- @RepeatedTest with RepetitionInfo parameter (BROKEN - reproducer)
- Conditional test execution
- Test skip/disable semantics

**Test Classes:**
- `DisabledTestsTest.java` - @Disabled tests + enabled tests mixed
- `RepeatedTestsTest.java` - **BUG REPRODUCER**: @RepeatedTest causes errors

**Known Issues:**
- P5-CTR-001: @RepeatedTest parameter injection fails
  - Expected: 3 successful repetitions
  - Actual: 3 errors due to RepetitionInfo injection failure

---

#### 5. kotest-advanced (Kotlin + Kotest)
```bash
cd kotest-advanced
mvn clean test

# Status: Prepared but requires Kotlin compiler setup
# Note: Kotlin compilation may need additional configuration
```

**What it tests (prepared):**
- Kotest FunSpec style (function-based)
- Kotest DescribeSpec style (BDD-style)
- Kotest StringSpec style (simple string tests)
- Kotlin test framework integration
- Custom assertions with Kotest matchers

**Test Classes:**
- `SimpleFunSpecTest.kt` - FunSpec style tests
- `BddDescribeSpecTest.kt` - DescribeSpec BDD style
- `SimpleStringSpecTest.kt` - StringSpec simple style

**Status:** Project structure complete, ready for Kotest testing

---

## Bug Reproducers

### P5-CTR-001: @RepeatedTest Parameter Injection (CRITICAL)
```bash
cd annotation-edge-cases
mvn test

# See: RepeatedTestsTest.java
# Issue: Plugin bytecode instrumentation breaks RepetitionInfo injection
```

### P5-CTR-002: Parameterized Test Display Names (MEDIUM)
```bash
cd custom-listeners
mvn test 2>&1 | grep "TimingListener"

# Note: Test names shown as [1], [2], [3] without parameter values
# Difficult to identify which parameter caused failure
```

### P5-CTR-003: Listener Overhead Not Accounted (MEDIUM)
```bash
cd custom-listeners
mvn test 2>&1 | grep "took.*ms"

# Listener timing captured but not used for test ordering optimization
```

---

## Test Coverage Summary

| Project | Framework | Tests | Pass | Fail | Skip | Status |
|---------|-----------|-------|------|------|------|--------|
| custom-listeners | JUnit 5 | 33 | 33 | 0 | 0 | ✅ |
| junit4-custom-runner | JUnit 4 | 11 | 11 | 0 | 0 | ✅ |
| mixed-junit-versions | Both | 4 | 4 | 0 | 0 | ✅ |
| annotation-edge-cases | JUnit 5 | 8 | 4 | 3 | 2 | ❌ Bug |
| kotest-advanced | Kotest | - | - | - | - | ⚠️ Prepared |
| **TOTAL** | **All** | **56** | **52** | **3** | **2** | **3 BUGS** |

---

## Dependency Chain Testing

### How Test Discovery Works
1. Plugin scans classpath for test classes
2. Uses bytecode instrumentation to track test execution
3. Builds dependency graph between tests
4. Optimizes test order based on dependencies

### What We Verified
- ✅ Parameterized tests are discovered correctly
- ✅ Inherited test methods are found
- ✅ Nested classes are discovered
- ✅ Disabled tests are properly skipped
- ✅ Custom listeners execute without breaking discovery
- ❌ RepeatedTest parameter injection breaks

---

## Known Issues and Workarounds

### P5-CTR-001: @RepeatedTest with Parameters
**Workaround:** Disable test-order plugin temporarily for tests using @RepeatedTest with parameters
```xml
<!-- In pom.xml, comment out or skip the plugin for affected projects -->
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <!-- Commented out for projects using @RepeatedTest
    <executions>
        <execution>
            <goals>
                <goal>combined</goal>
            </goals>
        </execution>
    </executions>
    -->
</plugin>
```

### P5-CTR-002 & P5-CTR-003
No workaround needed - functionality works, just suboptimal.

---

## Future Testing Areas

1. **Kotest Comprehensive Testing**
   - All spec styles (FunSpec, DescribeSpec, StringSpec, ShouldSpec, etc.)
   - Kotest data-driven testing
   - Kotest custom test context injection
   - Kotest parameterized tests

2. **Advanced Parameterization**
   - 100+ parameter combinations
   - Complex custom parameter sources
   - CSV files as parameter sources
   - Database-backed parameter sources

3. **Concurrent Test Execution**
   - Parallel test execution with test-order
   - Thread safety of listener tracking
   - Race conditions in test discovery

4. **Custom TestExtension and Listener Chains**
   - Multiple listeners in same test run
   - Extension interaction
   - Listener exception handling

5. **Dynamic Test Discovery**
   - @TestFactory and dynamic tests
   - Generated test cases
   - Test generation patterns

---

## Running Specific Test Scenarios

### Test Discovery Performance
```bash
cd custom-listeners
time mvn clean compile test -DskipTests
# First run discovers tests and creates cache
```

### Test Order Consistency
```bash
cd custom-listeners
mvn test -q  # Run once
mvn test -q  # Run again - should use cache and same order
```

### Listener Integration
```bash
cd custom-listeners
mvn test 2>&1 | grep "\[TimingListener\]"
# Should show all test executions with timing info
```

### Mixed Framework Support
```bash
cd mixed-junit-versions
mvn test -q
# Both JUnit 4 and 5 should run without interference
```

---

## Documentation Files

- `PHASE-5-CTR-BUG-HUNT-REPORT.md` - Detailed bug hunt findings
- `PHASE-5-CTR-FINAL-SUMMARY.txt` - Executive summary
- `LIVE-BUG-REPORT.md` - All bugs documented (includes P5-CTR bugs)
- This file: Test project guide and master index

---

## Questions and Further Investigation

If you encounter issues with these projects:

1. **Check Java version:** `java -version` (must be 11+)
2. **Check Maven version:** `mvn -v` (must be 3.6.0+)
3. **Check plugin build:** Run `mvn clean install -DskipTests` in plugin root
4. **Check test output:** Redirect stderr to file: `mvn test 2>&1 > test.log`
5. **Review listener logs:** Look for `[TimingListener]` or other custom output
6. **Compare with plugin disabled:** Comment out plugin config in pom.xml

---

## Contributing New Tests

To add new test scenarios:

1. Create new project directory: `p5-ctr-projects/my-test-project/`
2. Copy structure from existing project
3. Add new test classes to `src/test/java/` or `src/test/kotlin/`
4. Update pom.xml with specific dependencies needed
5. Add reproducer documentation
6. Run: `mvn clean test`
7. Document results in this index

---

Last Updated: 2026-04-21
Status: Phase 5 CTR Bug Hunt Complete
Bugs Found: 3 (1 CRITICAL, 2 MEDIUM)
Test Projects: 5 complete, 1 requires Kotlin setup
Next Action: Fix P5-CTR-001 before next release
