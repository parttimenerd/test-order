# Bug Report: test-order Plugin (0.1.0-SNAPSHOT)

Tested as a new user following the README instructions, using fresh standalone projects.
All sample projects are in the workspace under `sample-fresh/`, `sample-groupid-mismatch/`,
`sample-multi/`, etc.

---

## BUG 1 — `uncommitted` change detection ignores new/staged files

**Severity**: Medium

**Steps to reproduce**:
```bash
cd sample-fresh
mvn test -Dtestorder.mode=learn        # build index
echo "// touch" >> src/main/java/com/myapp/Greeter.java
git add src/main/                       # stage the changes
mvn test -Dtestorder.changeMode=uncommitted
```

**Expected**: Plugin detects `com.myapp.Greeter` as changed (it's staged/unstaged).

**Actual**: `[test-order] No changed classes detected — running tests in default order.`

**Root cause**: The `uncommitted` mode likely runs `git diff` which only shows
modifications to tracked files, not newly added (`??` untracked) or staged (`A`)
files. The README says it covers "staged + unstaged changes".

**Note**: This is especially problematic for new projects where ALL files are
untracked. The `since-last-run` hash-based mode works correctly for this case.

---

## BUG 2 — Silent failure when `groupId` doesn't match package name

**Severity**: High

**Steps to reproduce**:
```bash
cd sample-groupid-mismatch   # groupId=org.acme, packages=com.myapp
mvn test -Dtestorder.mode=learn
mvn test-order:dump
```

**Expected**: Agent instruments `com.myapp.*` classes and captures dependencies.

**Actual**: Agent filters by `org.acme` (the groupId), instruments nothing,
captures zero actual dependencies. The dump shows test class names but no deps.
No warning or error is emitted.

**Impact**: User follows the README, gets a seemingly valid index, but `order`
mode can never prioritize tests because there are no dependency links. This is
a completely silent failure.

**Suggested fix**: Emit a WARNING when learn mode produces an index where all
test classes have zero (or only self-reference) dependencies. Something like:
`[test-order] WARNING: No dependencies were captured. If your source packages
differ from your groupId, set -Dtestorder.includePackages=your.package.prefix`

---

## BUG 3 — `show-order` doesn't display test classes not in the index

**Severity**: Low

**Steps to reproduce**:
```bash
cd sample-fresh
mvn test -Dtestorder.mode=learn   # captures 4 test classes
# Add a new test class (not in index)
cat > src/test/java/com/myapp/NewFeatureTest.java << 'EOF'
package com.myapp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NewFeatureTest {
    @Test void test() { assertTrue(true); }
}
EOF
mvn test-order:show-order -Dtestorder.changed.classes=com.myapp.MathService
```

**Expected**: Shows 5 test classes including `NewFeatureTest` with score 15
(newTest bonus).

**Actual**: Shows only 4 test classes. `NewFeatureTest` is invisible even
though it would get the *highest* score at runtime.

**Impact**: Preview doesn't match actual execution order. Users can't see that
new tests get prioritized.

---

## BUG 4 — `mvn test-order:aggregate` destroys valid index after learn mode

**Severity**: Critical

**Steps to reproduce**:
```bash
cd sample-fresh
rm -f test-dependencies.lz4 && rm -rf target
mvn test -Dtestorder.mode=learn         # creates valid index (4 test classes)
mvn test-order:dump                     # shows 4 test classes ✓
mvn test-order:aggregate                # "Aggregated 0 test classes"
mvn test-order:dump                     # EMPTY — no test classes!
```

**Expected**: `aggregate` should either (a) be a no-op if `.deps` files are
already consumed, or (b) not overwrite a valid index with an empty one.

**Actual**: `aggregate` reads the empty `target/test-order-deps/` directory
(deps files were already consumed by learn mode) and overwrites the valid
`test-dependencies.lz4` with an empty index. The README explicitly tells users
to run `mvn test-order:aggregate` as a separate step.

**Impact**: Users following the README destroy their index immediately after
creating it.

**Suggested fix**: Either (a) have `aggregate` refuse to write an empty index
over a non-empty one, or (b) remove the separate `aggregate` step from the
README since learn mode already aggregates inline.

---

## BUG 5 — `CLASS_INIT` instrumentation mode produces garbage index

**Severity**: High

**Steps to reproduce**:
```bash
cd sample-fresh
rm -f test-dependencies.lz4 && rm -rf target
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentationMode=CLASS_INIT
mvn test-order:dump
```

**Expected**: 4 test classes, each with their own relevant dependencies.

**Actual**: Only 1 test class (`ValidatorTest`) appears, and it has ALL 8
classes (4 sources + 4 tests) as dependencies. The other 3 test classes are
missing entirely.

**Root cause**: With `reuseForks=false`, JUnit class discovery loads all test
classes in each fork. Since `CLASS_INIT` instruments `<clinit>`, any loaded
class counts as a dependency. Also, only one test's deps seem to survive
(likely the last fork overwrites or only one flush happens).

---

## BUG 6 — `select` goal doesn't actually filter Surefire includes

**Severity**: High

**Steps to reproduce**:
```bash
cd sample-fresh
mvn test-order:select test \
    -Dtestorder.changed.classes=com.myapp.Greeter \
    -Dtestorder.select.topN=2
cat target/test-order-selected.txt    # shows 2 test classes
cat target/test-order-remaining.txt   # shows 2 test classes
# But output shows ALL 4 test classes ran (13 total tests)
```

**Expected**: Only the 2 selected tests should run (GreeterTest, MathServiceTest).

**Actual**: All 4 test classes run. The `select` goal writes the correct files
but doesn't actually restrict Surefire to the selected subset.

**Impact**: The CI workflow from the README (`select` → `run-remaining`) is
broken. There's no fast subset; all tests run every time.

---

## BUG 7 — `run-remaining` goal doesn't filter tests either

**Severity**: High (same root cause as BUG 6)

**Steps to reproduce**:
```bash
cd sample-fresh
# After select (BUG 6 above)
mvn test-order:run-remaining test
# Output: "Running 2 remaining test classes"
# Actual: all 4 test classes run (13 total tests)
```

**Expected**: Only `MessageFormatterTest` and `ValidatorTest` should run.

**Actual**: All 4 test classes run despite the log saying "Running 2 remaining".

---

## BUG 8 — `.test-order-state` file is never created

**Severity**: Medium

**Steps to reproduce**:
```bash
cd sample-fresh
mvn test -Dtestorder.mode=learn
mvn test -Dtestorder.mode=order -Dtestorder.changed.classes=com.myapp.Greeter
mvn test -Dtestorder.mode=order -Dtestorder.changed.classes=com.myapp.Greeter
mvn test -Dtestorder.mode=order -Dtestorder.changed.classes=com.myapp.Greeter
ls .test-order-state   # does not exist
```

**Expected**: After order-mode runs, `.test-order-state` should contain
per-test score breakdowns, pass/fail outcomes, and APFD metrics (as the
README says: "Every order-mode test run records a quality snapshot to
`.test-order-state`").

**Actual**: File is never created, regardless of how many order-mode runs you
do, even with failing tests.

**Impact**: The entire `optimize` workflow is broken because there's no state
to optimize. The README's CI setup for weekly optimization is dead code.

---

## BUG 9 — `testorder.weights.file` property has no effect

**Severity**: Medium

**Steps to reproduce**:
```bash
cd sample-fresh
cat > my-weights.txt << 'EOF'
newTest = 25
changedTest = 15
maxFailure = 3
speed = 2
speedPenalty = 2
depOverlap = 50
EOF
mvn test-order:show-order \
    -Dtestorder.changed.classes=com.myapp.Greeter \
    -Dtestorder.weights.file=my-weights.txt
# Scores are 3 and 2 (default weights)

mvn test-order:show-order \
    -Dtestorder.changed.classes=com.myapp.Greeter \
    -Dtestorder.score.depOverlap=50
# Scores are 25 and 17 (correct!)
```

**Expected**: Weights file should override default weights, producing higher
scores (depOverlap=50 should give score ~25 for GreeterTest).

**Actual**: Weights file is completely ignored. Only individual system
properties (`-Dtestorder.score.X=Y`) work.

---

## BUG 10 — CLI `changed` command: mode enum mismatch + NPE

**Severity**: Medium

**Steps to reproduce**:
```bash
CLI_JAR=~/.m2/repository/me/bechberger/test-order-junit/0.1.0-SNAPSHOT/test-order-junit-0.1.0-SNAPSHOT-jar-with-dependencies.jar

# The help text says default is "since-last-run", but:
java -jar "$CLI_JAR" changed --mode=since-last-run
# Error: 'since-last-run' is not a valid value

java -jar "$CLI_JAR" changed --mode=SINCE_LAST_RUN
# Error: Cannot invoke "java.nio.file.Path.getFileSystem()" because "path" is null

java -jar "$CLI_JAR" changed --mode=SINCE_LAST_RUN \
    --hash-file=.test-order-hashes.lz4
# Same NPE
```

**Two sub-issues**:
1. The enum doesn't accept hyphenated values (`since-last-run`) despite the
   help text using hyphens.
2. Even with the correct uppercase format (`SINCE_LAST_RUN`), the command
   crashes with NPE, even when `--hash-file` is provided explicitly.

---

## BUG 11 — Multi-module: `dump`/`show-order` fail with default index path

**Severity**: Low

**Steps to reproduce**:
```bash
cd sample-multi   # multi-module project (core + web)
mvn test -Dtestorder.mode=learn    # works, creates .test-order/test-dependencies.lz4
mvn test-order:dump                # FAILS: "Dependency index not found: .../test-dependencies.lz4"
```

**Expected**: Goals should know that multi-module stores the index at
`.test-order/test-dependencies.lz4`.

**Actual**: Goals look for `test-dependencies.lz4` at the project root.

**Workaround**: `mvn test-order:dump -Dtestorder.index=.test-order/test-dependencies.lz4`

---

## What Works Well

These features all work correctly:

- **Learn mode** with `FULL` and `METHOD_ENTRY` instrumentation (single-module)
- **Order mode** with explicit changed classes — correct test prioritization
- **Auto mode** with hash-based change detection — detects changes, reorders tests
- **Changed test detection** — test source modifications detected via test hash snapshots
- **Cross-dependency tracking** — MessageFormatterTest correctly depends on both Greeter and MessageFormatter
- **New test bonus** — unrecognized test classes run first with 15-point bonus
- **Multi-module learn + order** — cross-module dependencies detected (UserControllerTest → UserService)
- **CLI `affected`, `stats`, `dump`, `run`** (with EXPLICIT mode)
- **Score customization** via individual system properties
- **Verbose agent logging** (`-Dtestorder.verboseFile=...`)
- **JUnit 5 and JUnit 6** both work correctly
- **`filterByGroupId` auto-detection** works when groupId matches package

---

## Summary Table

| # | Bug | Severity | Category |
|---|-----|----------|----------|
| 1 | `uncommitted` ignores new/staged files | Medium | Change detection |
| 2 | Silent failure with groupId ≠ package | High | Instrumentation |
| 3 | `show-order` hides new test classes | Low | Display |
| 4 | `aggregate` destroys valid index | Critical | Data loss |
| 5 | `CLASS_INIT` mode produces garbage | High | Instrumentation |
| 6 | `select` doesn't filter Surefire | High | Test selection |
| 7 | `run-remaining` doesn't filter either | High | Test selection |
| 8 | `.test-order-state` never created | Medium | State management |
| 9 | `weights.file` property ignored | Medium | Configuration |
| 10 | CLI `changed` mode enum + NPE | Medium | CLI |
| 11 | Multi-module default index path wrong | Low | Multi-module |
