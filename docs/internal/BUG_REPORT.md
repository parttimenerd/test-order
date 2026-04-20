# test-order Plugin Bug Report

## BUG #1: Learn mode does not collect test dependency data
**Severity**: CRITICAL - Core feature completely broken

**Description**:
The learn mode (`-Dtestorder.mode=learn`) claims to attach a Java agent to record test dependencies, but the agent does NOT collect any data. The `target/test-order-deps/` directory remains empty after running tests in learn mode.

**Reproducer**:
```bash
cd /tmp/test-simple
# Set up simple Maven project with JUnit5
mvn clean test -Dtestorder.mode=learn
ls target/test-order-deps/
# Result: directory is empty, no *.deps files created
```

**Expected Behavior** (from README):
"Results are written as `.deps` files and aggregated into a dependency index."

**Actual Behavior**:
- Plugin logs: "[test-order] Learn mode (FULL): attaching agent, default fork mode"
- No *.deps files appear in target/test-order-deps/
- Running `mvn test-order:aggregate` reports: "Aggregated 0 test classes"

**Root Cause**: The Java agent is not actually instrumenting the test classes or is not writing the dependency output files.

**Impact**: Complete workflow breakdown - users cannot build dependency index at all. Order mode becomes non-functional.

---

## BUG #2: Documentation incomplete - missing aggregate step
**Severity**: HIGH - Users will fail to follow documented workflow

**Description**:
The README states the workflow for "Normal workflow" and "Learn mode" but does not explicitly document that users MUST run `mvn test-order:aggregate` after `mvn test -Dtestorder.mode=learn` to create the dependency index. 

**Documentation Says**:
```
1. **Learn mode** — A Java agent instruments application classes to record which ones each test class exercises. Results are written as `.deps` files and aggregated into a dependency index.

### Normal workflow

### One-time setup

Add the plugin to your `pom.xml` (see [Quick start](#quick-start)), then run learn mode once to build the dependency index:

```bash
mvn test -Dtestorder.mode=learn
```
```

**What's Missing**:
The documentation should explicitly state:
```bash
mvn test -Dtestorder.mode=learn
mvn test-order:aggregate  # Users must run this step!
```

**User Experience Problem**: 
- User follows README exactly as written
- Runs `mvn test -Dtestorder.mode=learn`  
- Tests pass but mechanism doesn't work (see Bug #1)
- User doesn't understand why nothing is happening

---

## BUG #3: dump command fails silently with empty output
**Severity**: MEDIUM - Confusing user experience

**Description**:
When running `mvn test-order:dump` on an empty index, the command succeeds (exit code 0) but produces no output except Maven headers. Users have no way to know if the dump worked or what data exists.

**Reproducer**:
```bash
cd /path/to/project
mvn test-order:dump
```

**Expected Behavior**:
- Either show the dumped index contents (even if empty)
- Or show an informative message like "Index is empty" or "Index contains 0 test classes"
- Or show the file path being dumped

**Actual Behavior**:
- Command exits with code 0 (success)
- No output (or nearly no output)  
- User cannot tell if command worked or if there's an error

**User Impact**:
User cannot diagnose why their test ordering isn't working. They can't verify that dependency data exists.

---

## BUG #4: show-order command shows empty output when data doesn't exist
**Severity**: MEDIUM - Confusing user experience

**Description**:
When running `mvn test-order:show-order` with no dependency index or no detected changes, the output is completely empty (not counting headers). The command should indicate WHY there's nothing to show.

**Reproducer**:
```bash
mvn clean test -Dtestorder.mode=order
mvn test-order:show-order
```

**Output**:
```
  #    Test Class  Score  Deps  Fail  Changed Duration
  —    —————————— —————— ————— ————— ———————— ————————
```

**Expected Behavior**:
One of:
- Show informative message: "No test classes in index" or "No changed classes detected"
- Show the detected/missing files: "Index file not found at: test-dependencies.lz4"
- Show all tests with score 0 if index is empty

**User Impact**:
User cannot determine if the plugin is working correctly or if something is misconfigured.

---

## BUG #5: Confusing behavior: "auto" mode doesn't work as advertised for simple projects
**Severity**: HIGH - Core workflow broken in simple cases

**Description**:
The README claims "Under the hood the plugin runs in `auto` mode: it detects changed source files (via git or hash snapshots), scores every test class, and configures JUnit to run the most relevant tests first."

However, in practice:
1. When you run `mvn test` on a fresh project with no changes detected, it says "No changed classes detected — running tests in default order"
2. No automatic learning is triggered
3. No dependency data is collected

**Reproducer**:
```bash
# Fresh  project with test-order plugin configured
mvn test  # First run, no changes
# Output: "[test-order] No changed classes detected — running tests in default order"
# Expected: Should learn dependencies on first run OR ask user to run learn mode
```

**What the README Says**:
"### Day-to-day development

Just run your tests normally — the plugin auto-detects changed files and reorders tests:

```bash
mvn test
```"

**Reality**:
- Plugin does NOT automatically enter learn mode
- Plugin does NOT create dependency index
- Plugin just runs tests in arbitrary order
- User must manually run `mvn test -Dtestorder.mode=learn` first

**User Experience Problem**:
The README suggests users can just run `mvn test` and things "just work". This is false. There's a hidden prerequisite (running learn mode and aggregate first) that's not clearly documented.

---

## BUG #6: No clear error message when dependency index file is malformed/corrupt
**Severity**: MEDIUM - Silent failure

**Description**:
If the `test-dependencies.lz4` file is corrupted or invalid, the plugin may fail silently or with a cryptic error.

**Expected Behavior**:
Clear error message like: "ERROR: Dependency index file is corrupted: test-dependencies.lz4"

---

## BUG #7: Instrumentation mode and fork settings not well documented
**Severity**: MEDIUM - Users may choose wrong configuration

**Description**:
The README mentions three instrumentation modes but doesn't clearly explain:
1. Which is the default?
2. When should each be used?
3. What are the performance implications?

The README states "FULL is the default because it is both the most precise and does not require single-fork mode" - but some working projects (spring-petclinic) use METHOD_ENTRY instead. Why would they do this if FULL is better?

**User Impact**: 
User doesn't know if their instrumentation mode choice is optimal.

---

## BUG #8: Change detection modes not well explained
**Severity**: LOW - Confusing option documentation

**Description**:
The README lists four change detection modes but doesn't explain when each is appropriate or what the default is.

| Mode | When to Use? |
|------|-------------|
| since-last-run | ??? |
| since-last-commit | ??? |
| uncommitted | ??? |
| explicit | ??? |

"Explicit" is mentioned in error output but not explained.

---

## BUG #9: Mode parameter is inconsistent between documentation and implementation
**Severity**: MEDIUM - Confusing user interface

**Description**:
The README sometimes uses:
- `mvn test -Dtestorder.mode=learn`
- `mvn test -Dtestorder.changeMode=explicit` (in error output from earlier testing)

Which one is correct? Are there multiple ways to set the mode? This is confusing.

---

## BUG #10: Combined mode in plugin config vs command line
**Severity**: MEDIUM - Unclear which takes precedence

**Description**:
The README shows you can configure mode in pom.xml:
```xml
<configuration>
  <mode>learn</mode>
</configuration>
```

But also use system property `-Dtestorder.mode=learn`. Which takes precedence? What if they conflict?

---

## BUG #11: No validation of groupId filter configuration  
**Severity**: LOW - Silent failure

**Description**:
The plugin logs: "[test-order] Filtering instrumentation to groupId: com.example"

But if this is wrong or doesn't match any classes, there's no warning or error. Agent silently instruments nothing.

---

## SUMMARY

| # | Bug | Severity | Quick Fix? |
|---|-----|----------|-----------|
| 1 | Agent not collecting deps | CRITICAL | No - root cause investigation needed |
| 2 | Documentation missing aggregate step | HIGH | Yes - update README |
| 3 | dump command silent failure | MEDIUM | Yes - add informative output |
| 4 | show-order silent failure | MEDIUM | Yes - add informative message |
| 5 | auto mode broken for simple projects | HIGH | Yes - better defaults or docs |
| 6 | No error on corrupt index | MEDIUM | Yes - add validation |
| 7 | Instrumentation mode not well documented | MEDIUM | Yes - improve docs |
| 8 | Change detection modes not explained | LOW | Yes - add docs |
| 9 | Mode parameter inconsistent | MEDIUM | Need investigation |
| 10 | Config precedence unclear | MEDIUM | Yes - document it |
| 11 | NoGroupId filter validation | LOW | Yes - add warning |

## BREAKING THE NORMAL WORKFLOW

According to the README, this should work:

```bash
# One-time setup
mvn test -Dtestorder.mode=learn
# BUT THIS DOESN'T CREATE DEPENDENCY INDEX (Bug #1)

# Day-to-day
mvn test  
# AND THIS DOESN'T WORK (Bug #5)
```

**What actually needs to happen**:
```bash
mvn clean test -Dtestorder.mode=learn
mvn test-order:aggregate  # Hidden required step!
# Now you have an index, but only if agent actually collected data (Bug #1)
```

The plugin is NOT production-ready for new users.
