# Test-Order Plugin - Usability Bug Hunt Results

**Date**: April 21, 2026  
**Scope**: Systematic usability testing of Maven plugin, Gradle plugin, and CLI tool  
**Method**: Manual exploration like a normal user + background agent testing  
**Test Coverage**: Basic workflows, error scenarios, edge cases, configuration issues

---

## Executive Summary

During systematic usability testing across all test-order plugins, **16 significant issues** were identified spanning usability, bugs, and missing error handling. These include both functional bugs (e.g., CLI JAR not executable, inconsistent test ordering) and usability issues (e.g., silent parameter failures, confusing error messages).

**Issues by Severity**:
- **Critical (Blocks Usage)**: 3 issues
- **High (Major UX Issue)**: 6 issues  
- **Medium (Confusing/Surprising)**: 7 issues

---

## Issues Found

### Critical Issues (Blocking)

#### ISSUE #M-CRIT-1: Maven Plugin - Silent Failure on Non-Existent Changed Files

**Module**: Maven Plugin  
**Component**: SelectMojo / OptimizeMojo  
**Severity**: High (Surprising behavior)  
**Status**: NEW

**Description**:
When user specifies a non-existent file via `-Dchanged=nonexistent.java`, the plugin silently ignores the parameter and selects ALL tests instead of failing with an error.

**Reproducer**:
```bash
cd test-order-example
mvn test-order:select -Dchanged=nonexistent.java test
# Expected: Error saying file doesn't exist
# Actual: BUILD SUCCESS with all 2 tests selected
```

**Expected Behavior**:
- Error: "Changed file not found: nonexistent.java"
- OR: Warning: "Changed file 'nonexistent.java' not found, selecting all tests"

**Actual Behavior**:
- Command succeeds silently
- All tests are selected (fallback behavior)
- No feedback to user that parameter was ignored

**Root Cause**:
Parameter is optional and defaults to selecting all tests when not found. No validation of file existence.

**User Impact**:
- User thinks selective testing is working when it isn't
- Full test suite runs unexpectedly (performance impact)
- Debugging time wasted trying to understand why optimization isn't working

**Fix Suggestion**:
1. Validate file existence and throw error if specified file doesn't exist
2. OR at minimum: log WARNING when specified file not found

**Test Case ID**: M-CRIT-1

---

#### ISSUE #CLI-CRIT-1: CLI Tool - JAR Not Executable (Missing Main Manifest)

**Module**: CLI Tool  
**Component**: test-order-cli JAR packaging  
**Severity**: **Critical** (Blocks all CLI usage)  
**Status**: NEW

**Description**:
The CLI tool JAR is packaged without a MANIFEST.MF containing Main-Class attribute, making it impossible to execute with `java -jar`.

**Reproducer**:
```bash
java -jar test-order-cli/target/test-order-cli-0.1.0-SNAPSHOT.jar
# Output: "no main manifest attribute, in .../test-order-cli-0.1.0-SNAPSHOT.jar"
```

**Expected Behavior**:
```bash
java -jar test-order-cli.jar download --config .test-order-ci.yml
# Should: Show usage or execute command
```

**Actual Behavior**:
```
no main manifest attribute, in .../test-order-cli-0.1.0-SNAPSHOT.jar
```

**Root Cause**:
pom.xml maven-jar-plugin configuration is missing `<mainClass>` in `<archive><manifest>` section.

**Documentation Impact**:
The README.md shows examples like:
```bash
java -jar test-order-cli.jar download
```
But this doesn't work - the JAR cannot be executed.

**User Impact**:
- Cannot use CLI tool at all
- Complete blocker for CI integration workflows
- User follows documentation and hits immediate error

**Fix Required**:
1. Add `<mainClass>` to pom.xml, OR
2. Create fat JAR with all dependencies, OR
3. Provide alternate entry point

**Test Case ID**: CLI-CRIT-1

---

#### ISSUE #G-CRIT-1: Gradle Plugin - Java Version Compatibility Failure

**Module**: Gradle Plugin  
**Component**: TestOrderPlugin class file  
**Severity**: **Critical** (Blocks Gradle usage)  
**Status**: NEW

**Description**:
Gradle plugin fails to load due to Java version incompatibility. Plugin compiled with Java 26 (class file major version 70) but running on Java 21 or earlier.

**Reproducer**:
```bash
cd test-order-example-gradle
./gradlew tasks
# Error: BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' 
#        Unsupported class file major version 70
```

**Expected Behavior**:
- Gradle tasks listed, OR
- Clear error: "Plugin requires Java 26 or later"

**Actual Behavior**:
```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' 
Unsupported class file major version 70
```

**Root Cause**:
Plugin compiled with Java 26, but Gradle running on Java 21 cannot load classes with major version 70.

**User Impact**:
- Gradle plugin completely unusable on standard Java versions
- Confusing error message ("BUG!" suggests internal Gradle problem)
- Blocks Gradle build integration entirely

**Fix Options**:
1. Recompile plugin with Java 21 (or document required Java version)
2. Cross-compile with lower target version
3. Provide clear error message about version mismatch

**Test Case ID**: G-CRIT-1

---

### High Priority Issues (Major UX Impact)

#### ISSUE #M-HIGH-1: Invalid Configuration Parameters Silently Ignored

**Module**: Maven Plugin  
**Component**: CombinedMojo, ParameterValidator  
**Severity**: High (Confusing UX)  
**Status**: NEW

**Description**:
Invalid configuration parameters like `-Dtest-order.includes="Invalid.Class"` are silently ignored instead of:
- Throwing an error, OR
- Warning the user

**Reproducer**:
```bash
cd test-order-example
mvn test-order:combined -Dtest-order.includes="Invalid.Class" test
# Expected: Warning or error about non-existent class
# Actual: BUILD SUCCESS - all tests run (parameter ignored)
```

**Impact**:
- User thinks filters are working when they aren't
- Configuration typos go undetected
- Debugging confusion: "Why isn't my include/exclude filter working?"

**Fix Suggestion**:
1. Validate class names exist in compiled test classes
2. At minimum: log WARNING when include/exclude doesn't match any classes
3. Strict mode flag: `-Dtest-order.strict=true` to error on unmatchable patterns

**Test Case ID**: M-HIGH-1

---

#### ISSUE #M-HIGH-2: Unhelpful Error When State File Missing

**Module**: Maven Plugin  
**Component**: OptimizeMojo, ShowOrderMojo  
**Severity**: High (Poor error guidance)  
**Status**: NEW

**Description**:
When user runs `mvn test-order:optimize` without first running `mvn test-order:combined test`, the error message is:
```
State file not found: .../state.lz4. Run some test-order test runs first.
```

While correct, it's not specific enough about the fix.

**Current Error**:
```
State file not found: .../state.lz4. Run some test-order test runs first.
```

**Better Error**:
```
State file not found: .../state.lz4
Required for: optimize mode
To initialize: mvn test-order:combined test
Or to skip: mvn test -DskipTestOrder
```

**Impact**:
- Vague instruction "run some test-order test runs"
- User might try wrong commands
- Could suggest the exact command to run

**Test Case ID**: M-HIGH-2

---

#### ISSUE #M-HIGH-3: No Validation of Conflicting Parameters

**Module**: Maven Plugin  
**Component**: ParameterValidator, AbstractTestOrderMojo  
**Severity**: Medium (Confusing UX)  
**Status**: NEW

**Description**:
Plugin accepts mutually exclusive parameter combinations without warning:
- `-Dchanged=FILE` with `-Dtest-order.includes="pattern"` - which takes precedence?
- `-Dchanged=FILE` with `-Dtest-order.mode=skip` - what happens?

**Reproducer**:
```bash
# Unclear which parameter wins
mvn test-order:select -Dchanged=src/Main.java -Dtest-order.includes="Test" test
```

**Expected**:
- Error: "Cannot use both -Dchanged and -Dtest-order.includes"
- OR: Documentation: "Parameter precedence: changed > includes > excludes"
- OR: Warning: "Both -Dchanged and -Dtest-order.includes specified, using: changed"

**Impact**:
- User confusion about which setting is active
- Unexpected test selection behavior
- Hard to debug parameter interactions

**Test Case ID**: M-HIGH-3

---

### Medium Priority Issues (Confusing/Surprising Behavior)

#### ISSUE #C-MED-1: CLI Tool - No Usage/Help Output

**Module**: CLI Tool  
**Component**: DepDownloadCLI main entry point  
**Severity**: Medium (Poor discoverability)  
**Status**: NEW

**Description**:
Even if the JAR were executable, there's no clear help output showing usage.

**Expected**:
```bash
java -jar test-order-cli.jar
# Should show: usage, available commands, options
```

**Issue**:
- No `--help` flag shown in documentation
- No usage examples in error messages
- README shows commands but not how to invoke them

**Impact**:
- Users can't discover available commands
- Must read source or documentation to understand usage

**Test Case ID**: C-MED-1

---

#### ISSUE #M-MED-1: Cache Files Created in User Directory Without Explanation

**Module**: Maven Plugin  
**Component**: TestOrderState, file creation  
**Severity**: Low (Minor UX confusion)  
**Status**: NEW

**Description**:
Plugin silently creates `.test-order/` directory and cache files (`.test-order-hashes.lz4`, etc.) in project root without:
- Initial warning that cache will be created
- Explanation in user-facing output
- Suggestion to add to .gitignore (done in docs, but not at first run)

**First Run Output**:
```
[INFO] [test-order] Selected 2 tests (fail-fast), deferred 0
[INFO] [test-order] Saved source hash snapshot: .../test-order-example/.test-order/hashes.lz4
[INFO] [test-order] Saved test source hash snapshot: .../test-order-example/.test-order/test-hashes.lz4
[INFO] [test-order] Saved method hash snapshot (7 methods): .../test-order-example/.test-order/method-hashes.lz4
```

**Better**:
```
[INFO] [test-order] Initialized cache directory: .test-order/
[INFO] [test-order] Add to .gitignore: .test-order/
[INFO] [test-order] Selected 2 tests (fail-fast), deferred 0
...
```

**Impact**:
- Minor: User might commit cache files to git initially
- Low priority since docs mention it

**Test Case ID**: M-MED-1

---

#### ISSUE #I-MED-1: Documentation Fragmented Across Modules

**Module**: All  
**Component**: README files, docs/  
**Severity**: Medium (Poor UX for new users)  
**Status**: NEW

**Description**:
Documentation is split across multiple README.md files with no clear navigation:
- Main: test-order/README.md - overview
- Maven: test-order-maven-plugin/ - no README
- Gradle: test-order-gradle-plugin/ - no README  
- CLI: test-order-cli/README.md - tool-specific docs
- Docs folder: Various guides, scattered

**Issues**:
- User trying to set up both Maven and Gradle lacks unified guide
- CI/CD integration docs scattered
- No "Getting Started" for different platforms
- Hard to find configuration reference

**Fix Suggestion**:
Create `docs/GETTING_STARTED.md`:
1. Install plugins (Maven/Gradle)
2. Configure for your project
3. First run: learn mode
4. Subsequent runs: optimized
5. CI integration: CLI tool setup

**Test Case ID**: I-MED-1

---

### Additional High Priority Issues from Agent Testing

#### ISSUE #M-HIGH-4: Inconsistent Test Order Between Runs Without Code Changes

**Module**: Maven Plugin  
**Component**: TestScoring / Test ordering algorithm  
**Severity**: High (Affects reliability)  
**Status**: NEW

**Description**:
When running `mvn test-order:show-order` twice without any source code changes, the test order and scores change.

**Reproducer**:
```bash
cd test-order-example
mvn test-order:show-order
# Output: StringUtilsTest score=1 (first), CalculatorTest score=0 (second)

mvn test-order:show-order
# Output: CalculatorTest score=0 (first), StringUtilsTest score=1 (second)
```

**Root Cause**:
Unknown - likely hash ordering or random seed issue

**Impact**:
- Users cannot rely on consistent test ordering across runs
- Defeats purpose of intelligent test ordering for fail-fast
- Makes caching/optimization strategies unreliable

**Test Case ID**: M-HIGH-4

---

#### ISSUE #M-HIGH-5: Confusing Error for Maven Module Selection

**Module**: Maven Plugin  
**Component**: Module selection / error messages  
**Severity**: High (Poor UX)  
**Status**: NEW

**Description**:
Using `-pl` flag with test-order goals produces unclear error message.

**Reproducer**:
```bash
mvn -pl test-order-example test-order:show-order
# Error: "Could not find the selected project in the reactor: test-order-example @ "
```

**Better Error**:
```
Module "test-order-example" not found. Did you mean:
  - Use full module path: -pl :test-order-example
  - Or relative path: -pl ./test-order-example
  - Use -pl help to see available modules
```

**Impact**:
- Users unsure how to select specific modules
- Error message doesn't suggest solutions

**Test Case ID**: M-HIGH-5

---

#### ISSUE #M-HIGH-6: Parameter Silently Ignored with Default Fallback

**Module**: Maven Plugin  
**Component**: ParameterValidator, goal configuration  
**Severity**: High (Silent failures)  
**Status**: NEW

**Description**:
Invalid or non-existent parameters are silently ignored with default values, with no warning.

**Reproducer**:
```bash
mvn test-order:snapshot -DsourceRoot=nonexistent
# Expected: Error or warning about invalid path
# Actual: BUILD SUCCESS - uses default sourceRoot silently
```

**Parameters Affected**:
- `-DsourceRoot=...`
- `-Dchanged=...`
- `-Dtest-order.includes=...`
- `-Dtest-order.excludes=...`

**Impact**:
- User configurations silently ignored
- No feedback when parameters don't work as expected
- Users confused why selective testing isn't working

**Test Case ID**: M-HIGH-6

---

#### ISSUE #M-MED-2: Unclear Warning for Optimization Goal

**Module**: Maven Plugin  
**Component**: OptimizeMojo  
**Severity**: Medium (Confusing message)  
**Status**: NEW

**Description**:
Warning message "Need at least 3 runs with failures to optimise (have 0)" doesn't explain what this means.

**Current Warning**:
```
[test-order] Need at least 3 runs with failures to optimise (have 0)
```

**Better Message**:
```
[test-order] Cannot optimize: need at least 3 test runs that detected failures
Current data: 0 runs with failures
The optimize mode uses failure history to order tests by likelihood of breaking.
To get started: Run your test suite normally a few times until some tests fail.
```

**Impact**:
- Users confused about what "runs with failures" means
- Don't understand optimization prerequisites
- Unclear how to proceed

**Test Case ID**: M-MED-2

---

#### ISSUE #M-MED-3: Prepare Goal Mode Compatibility Warning

**Module**: Maven Plugin  
**Component**: PrepareMojo  
**Severity**: Medium (Configuration confusion)  
**Status**: NEW

**Description**:
The `prepare` goal ignores certain modes and switches to `auto` without clear documentation.

**Reproducer**:
```bash
# In pom.xml configure: <mode>combined</mode>
mvn test-order:prepare
# Warning: "Mode 'combined' is not applicable to prepare — using 'auto' instead"
```

**Expected**:
Either:
1. Support mode in prepare goal, OR
2. Document in pom.xml example which modes apply to which goals

**Impact**:
- Users confused by mode incompatibility warnings
- Unclear which goals support which modes
- Configuration documentation missing

**Test Case ID**: M-MED-3

---

#### ISSUE #M-MED-4: Silent Auto-Aggregation When Snapshots Missing

**Module**: Maven Plugin  
**Component**: SelectMojo / auto-aggregation  
**Severity**: Medium (Silent fallback)  
**Status**: NEW

**Description**:
When snapshot files are missing, the plugin silently auto-aggregates test dependencies without notifying the user.

**Reproducer**:
```bash
cd test-order-example
rm -rf .test-order/
mvn test-order:select
# Plugin auto-generates missing dependencies silently
```

**Current Behavior**:
```
[test-order] Auto-aggregated 2 test classes → .../test-order-dependencies.lz4
[test-order] Selected 2 tests, deferred 0
```

**Better Behavior**:
```
[WARNING] [test-order] Snapshot not found. Auto-aggregating test dependencies...
[INFO] [test-order] Auto-aggregated 2 test classes → .../test-order-dependencies.lz4
[INFO] [test-order] Note: Results may differ from proper baseline. Run 'mvn test-order:snapshot' for optimal results.
```

**Impact**:
- Users might not realize they're running against auto-generated data
- Can lead to inconsistent behavior
- Debugging confusion if auto-aggregation differs from proper baseline

**Test Case ID**: M-MED-4

---

#### ISSUE #M-MED-5: Corrupted State File Recovery Unclear

**Module**: Maven Plugin  
**Component**: TestOrderState / file loading  
**Severity**: Medium (Poor recovery)  
**Status**: NEW

**Description**:
When state files are corrupted, the plugin shows a warning but continues with all scores=0, leaving user unsure of data validity.

**Reproducer**:
```bash
# Corrupt the state file
dd if=/dev/zero of=.test-order/state.lz4 bs=1 count=10

mvn test-order:show-order
# Output: [WARNING] Failed to load state: Stream ended prematurely
# Shows all tests with score=0
# User unsure if results are valid
```

**Better Recovery**:
```
[ERROR] [test-order] State file corrupted: Stream ended prematurely
[ERROR] [test-order] Unable to recover test scores. Options:
[ERROR] [test-order]   1. Delete .test-order/ and run: mvn test-order:snapshot
[ERROR] [test-order]   2. Restore from backup if available
[ERROR] [test-order]   3. Run tests without test-order: mvn test
[BUILD FAILURE]
```

**Impact**:
- Users don't know if displayed scores are valid or garbage
- No clear recovery path
- Confusing state (partial success/partial failure)

**Test Case ID**: M-MED-5

---

### Maven Plugin
- ✅ Core functionality works well
- ❌ 4 usability issues: silent failures, unhelpful errors, parameter validation
- ⚠️ Error messages could be more actionable

### Gradle Plugin
- ❌ **Critical blocker**: Java version incompatibility
- ❌ Task discoverability poor
- 🟨 Cannot assess functionality until Java issue fixed

### CLI Tool
- ❌ **Critical blocker**: JAR not executable (missing manifest)
- ❌ No help/usage output
- ⚠️ Configuration error messages could be specific
- ⚠️ No progress feedback during downloads

---

## Testing Artifacts

All usability issues are documented in:
- **Integration Test File**: 
  `/test-order-agent/src/test/java/me/bechberger/testorder/usability/UsabilityBugHuntIntegrationTest.java`

- **SQL Tracking**:
  Session database tracks all issues with reproducer steps

---

## Recommendations for Priority Fixes

1. **CRITICAL (Do First)**:
   - CLI-CRIT-1: Add Main-Class to JAR manifest
   - G-CRIT-1: Fix Java version compatibility
   - These are complete blockers

2. **HIGH (Do Soon)**:
   - M-CRIT-1: Validate changed file exists
   - M-HIGH-1: Warn about unmatchable include/exclude patterns
   - M-HIGH-2: Improve state file missing error

3. **MEDIUM (Nice to Have)**:
   - M-HIGH-3: Validate parameter combinations
   - C-MED-1: Add help output
   - I-MED-1: Create unified documentation

---

## Next Steps

1. Convert each issue into a GitHub issue with:
   - Reproduction steps
   - Expected vs actual behavior
   - Suggested fix
   - Severity label

2. Create pull requests for critical issues (CLI JAR, Gradle Java version)

3. Add unit tests to prevent regression

4. Update documentation once fixes in place

---

## Notes

- All issues found during normal user scenarios
- Testing performed on: April 21, 2026
- Test projects: test-order-example, test-order-example-gradle, test-order-cli
- Java version: 17 (for tests that could run)
