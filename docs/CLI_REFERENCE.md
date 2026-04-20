# test-order CLI Reference Guide

Complete reference for all Maven plugin goals, Gradle plugin options, and configuration parameters.

---

## Table of Contents

1. [Maven Plugin Goals](#maven-plugin-goals)
2. [Configuration Parameters](#configuration-parameters)
3. [Parameter Validation](#parameter-validation)
4. [Common Use Cases](#common-use-cases)
5. [Property Naming Convention](#property-naming-convention)
6. [Error Messages & Troubleshooting](#error-messages--troubleshooting)

---

## Maven Plugin Goals

### Goal: `prepare`

Prepares the test environment by setting up the dependency index and state files.

**Usage**: `mvn test-order:prepare`

**When to use**: 
- Before first test run
- After resetting test state
- To validate configuration

**Parameters**: Uses common parameters (see [Configuration Parameters](#configuration-parameters))

**Example**:
```bash
mvn test-order:prepare
```

---

### Goal: `snapshot`

Creates a snapshot of test dependencies (learns test class dependencies via bytecode analysis).

**Usage**: `mvn test-order:snapshot test`

**When to use**:
- To initialize dependency index without running tests
- To update index after major code changes
- When you want to analyze dependencies but skip test execution

**Key Parameters**:
- `changeMode`: Set to `auto` (default) or `since-last-commit` to analyze changed classes
- `includePackages`: Add additional packages to instrument
- `sourceRoot`: Override auto-detected source root

**Example**:
```bash
# Snapshot with git-based change detection
mvn test-order:snapshot test -Dtestorder.change-mode=since-last-commit

# Snapshot with specific packages
mvn test-order:snapshot test -Dtestorder.include-packages=com.example.util,com.example.service
```

---

### Goal: `aggregate`

Aggregates individual `.deps` files (from learn mode) into a single dependency index.

**Usage**: `mvn test-order:aggregate`

**When to use**:
- After running many tests in learn mode
- To consolidate `.deps` files into the index

**Parameters**:
- `depsDir`: Directory containing `.deps` files (default: `${project.build.directory}/test-order-deps`)

**Example**:
```bash
# Aggregate deps files into index
mvn test-order:aggregate

# Aggregate from custom directory
mvn test-order:aggregate -Dtestorder.deps-dir=/custom/deps/path
```

---

### Goal: `combined` (Recommended for Local Development)

Complete local development workflow in one command:
1. Learn mode if no index exists
2. Select fast subset (top-N + random + new tests)
3. Run selected tests only
4. Write deferred tests to file
5. Periodically optimize weights

**Usage**: `mvn test-order:combined test`

**When to use**: Daily development work (fastest feedback loop)

**Key Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `selectTopN` | Integer | 20 | Number of top-scored tests to always run |
| `selectRandomM` | Integer | 10 | Number of random tests for diversity |
| `selectSeed` | Long | None | Seed for reproducible random selection |
| `runRemaining` | Boolean | true | Show reminder for deferred tests |
| `optimizeEvery` | Integer | 10 | Optimize weights every N successful runs (0=never) |
| `filterByGroupId` | Boolean | true | Use project groupId if no source packages detected |
| `includePackages` | String | None | Additional packages to instrument |

**Example: Fast feedback during development**
```bash
# Run selected tests only (20 top + 10 random)
mvn test-order:combined test

# Include custom packages
mvn test-order:combined test -Dtestorder.include-packages=com.example.util

# Use fixed seed for reproducible selection
mvn test-order:combined test -Dtestorder.select-seed=12345

# Run more tests (50 top + 20 random)
mvn test-order:combined test -Dtestorder.select-top-n=50 -Dtestorder.select-random-m=20

# Disable automatic weight optimization
mvn test-order:combined test -Dtestorder.combined-optimize-every=0

# Run with verbose logging for debugging
mvn test-order:combined test -Dtestorder.verbose-file=verbose.log
```

**Output Files**:
- `test-order-selected.txt`: List of tests that were executed
- `test-order-remaining.txt`: List of deferred tests

**Next Step**: After combined test, run remaining tests:
```bash
mvn test-order:run-remaining test
```

---

### Goal: `run-remaining`

Runs tests that were deferred by previous `combined` or `select` invocation.

**Usage**: `mvn test-order:run-remaining test`

**When to use**: After `combined` run to test the remaining subset

**Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `selectRemainingFile` | String | `${project.build.directory}/test-order-remaining.txt` | File containing deferred test list |

**Example**:
```bash
# Run remaining tests after combined
mvn test-order:run-remaining test

# Use custom remaining file
mvn test-order:run-remaining test -Dtestorder.select-remaining-file=/tmp/my-remaining.txt
```

---

### Goal: `select`

Selects a subset of tests without running them. Useful for CI/CD pipelines.

**Usage**: `mvn test-order:select`

**When to use**:
- To generate test selection file for CI pipeline
- To preview which tests would run
- To integrate with custom test runners

**Key Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `selectTopN` | Integer | 20 | Number of top-scored tests |
| `selectRandomM` | Integer | 10 | Number of random tests |
| `selectSeed` | Long | None | Seed for random selection |
| `selectedFile` | String | `${project.build.directory}/test-order-selected.txt` | Output file with selected tests |

**Example**:
```bash
# Select tests without running
mvn test-order:select

# Select and write to custom file
mvn test-order:select -Dtestorder.selected-file=/tmp/selected-tests.txt

# Select 100 tests reproducibly
mvn test-order:select -Dtestorder.select-top-n=60 -Dtestorder.select-random-m=40 -Dtestorder.select-seed=42
```

---

### Goal: `show-order`

Displays test execution order with scores and reasoning.

**Usage**: `mvn test-order:show-order`

**When to use**:
- To understand why tests are ordered this way
- To debug low coverage areas
- To verify scoring configuration

**Key Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `scoreNewTest` | Integer | ? | Score bonus for new tests not in index |
| `scoreChangedTest` | Integer | ? | Score bonus for tests with changed source |
| `scoreMaxFailure` | Integer | ? | Maximum score from failure frequency |
| `scoreSpeed` | Integer | ? | Score bonus for fast tests |
| `scoreDepOverlap` | Integer | ? | Max score from dependency overlap |

**Example**:
```bash
# Show test execution order with scores
mvn test-order:show-order

# Show order with custom scoring
mvn test-order:show-order \
  -Dtestorder.score-new-test=50 \
  -Dtestorder.score-changed-test=40 \
  -Dtestorder.score-max-failure=30
```

**Output Format**:
```
#    Test Class        Score  Deps  Fail  Changed Duration
—    ———————————————— —————— ————— ————— ———————— ————————
1.   MyTest            150    3     1     true    0.050s
2.   IntegrationTest   120    2     0     false   0.150s
...
```

---

### Goal: `dump`

Dumps the dependency index contents in human-readable format.

**Usage**: `mvn test-order:dump`

**When to use**:
- To inspect index contents
- To debug dependency detection
- To export data for analysis

**Parameters**: Uses common parameters

**Example**:
```bash
# Dump index to console
mvn test-order:dump

# Dump to file
mvn test-order:dump > dependency-index.txt
```

---

### Goal: `optimize`

Optimizes scoring weights based on test failure history.

**Usage**: `mvn test-order:optimize`

**When to use**:
- Periodically after many test runs
- To improve prediction accuracy
- When scoring feels off

**Key Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `weightsFile` | String | None | Optional path to override weights |

**Example**:
```bash
# Optimize weights
mvn test-order:optimize

# Optimize with custom weights
mvn test-order:optimize -Dtestorder.weights-file=/path/to/weights.properties
```

---

### Goal: `dashboard`

Generates a self-contained HTML dashboard visualising the test-order scoring,
dependency graph, run history, and distributions for your test suite.

**Usage**: `mvn test-order:dashboard`

**When to use**:
- To understand *why* specific tests are prioritised
- To debug the scoring system (Weights Explorer tab lets you try "what-if" weight changes live)
- To review historical APFD trends and failure patterns
- To share a snapshot of test health with your team (single HTML file, no server needed)

**Output**: `target/test-order-dashboard/index.html` — a fully self-contained HTML file
(Vue 3 + Chart.js + D3 via CDN). Open with any browser via `file://`.

**Key Parameters**:
| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `dashboardOutput` | `testorder.dashboard.output` | `target/test-order-dashboard/index.html` | Path for the generated HTML file |
| `coverageDir` | `testorder.dashboard.coverageDir` | `target/site/jacoco` | JaCoCo report dir (reserved for future use) |
| `openBrowser` | `testorder.dashboard.open` | `false` | Auto-open in default browser after generation |

**Dashboard tabs**:

| Tab | Description |
|-----|-------------|
| **Test Explorer** | Sortable, filterable table of all tests with rank, score, dep overlap, fail score, duration, and badges (CHANGED/NEW/FAILING/FAST/SLOW/STATIC) |
| **Score Breakdown** | Horizontal stacked bar chart showing each scoring component for the selected test, with plain-English explanation cards |
| **Dependency Graph** | D3 force-directed graph of source class dependencies; toggle between Focus (selected test only), Changed subgraph (all tests touching changed classes), and Full |
| **Project Timeline** | 4 synchronized charts: APFD trend, failures per run, time-to-first-failure, and test count — with a crosshair synchronized across all charts |
| **Per-Test History** | Duration EMA trend, pass/fail strip (colored squares per run), score over time, and run position for the selected test |
| **Distributions** | Score histogram, duration log-bucket histogram, dependency count distribution, and top-20 tests by failure score |
| **Weights Explorer** | Interactive sliders to adjust all 9 scoring weights; re-scores every test live and shows the rank change table (Δ highlighted if >5) |
| **Coverage Treemap** | Placeholder — JaCoCo treemap integration planned for a future release |

**Examples**:
```bash
# Generate dashboard
mvn test-order:dashboard

# Generate and open in browser immediately
mvn test-order:dashboard -Dtestorder.dashboard.open=true

# Write to a custom location
mvn test-order:dashboard -Dtestorder.dashboard.output=/tmp/my-dashboard.html
```

**In pom.xml**:
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <configuration>
    <dashboardOutput>${project.build.directory}/test-order-dashboard/index.html</dashboardOutput>
    <openBrowser>false</openBrowser>
  </configuration>
</plugin>
```

---

### Goal: `coverage` (test-order-coverage-mojo)

Analyzes test coverage across project and identifies least tested classes.

**Usage**: `mvn test-order:coverage`

**When to use**:
- To identify coverage gaps
- To prioritize testing effort
- To generate coverage reports
- In CI/CD for coverage tracking

**Key Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `threshold` | Integer | 50 | Coverage percentage threshold (0-100) |
| `outputDir` | File | `${project.build.directory}/coverage-reports` | Output directory for reports |
| `outputFormat` | String | `comprehensive` | Format: comprehensive, markdown, or json |
| `includeModules` | String | None | Comma-separated modules to include |

**Output Files**:
- `COVERAGE_BY_MODULE.md` - Module-level coverage summary
- `LEAST_TESTED_CLASSES.md` - Classes below threshold, grouped by severity
- `COVERAGE_RECOMMENDATIONS.md` - Actionable recommendations
- `coverage-metrics.json` - Machine-readable metrics

**Example**:
```bash
# Generate coverage reports with 50% threshold
mvn test-order:coverage

# Custom threshold (40%)
mvn test-order:coverage -Dcoverage.threshold=40

# JSON output for CI integration
mvn test-order:coverage -Dcoverage.outputFormat=json

# Include specific modules only
mvn test-order:coverage -Dcoverage.includeModules=core,cli

# Comprehensive example
mvn test-order:coverage \
  -Dcoverage.threshold=60 \
  -Dcoverage.outputDir=/var/reports \
  -Dcoverage.includeModules=core,api \
  -Dcoverage.outputFormat=comprehensive
```

---

## Configuration Parameters

### Common Parameters (All Goals)

These parameters are available for all test-order goals.

#### Index & State Files

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `indexFile` | `testorder.index` | String | `${project.basedir}/test-dependencies.lz4` | Dependency index file location |
| `stateFile` | `testorder.state-file` | String | `${project.basedir}/.test-order-state` | Test execution state and history |
| `depsDir` | `testorder.deps-dir` | String | `${project.build.directory}/test-order-deps` | Temporary deps file directory (learn mode) |

#### Hash Files (Change Detection)

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `hashFile` | `testorder.hash-file` | String | `${project.basedir}/.test-order-hashes.lz4` | Hash file for main source (git fallback) |
| `testHashFile` | `testorder.test-hash-file` | String | `${project.basedir}/.test-order-test-hashes.lz4` | Hash file for test source |
| `methodHashFile` | `testorder.method-hash-file` | String | `${project.basedir}/.test-order-method-hashes.lz4` | Hash file for test methods |

#### Source Directories

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `sourceRoot` | `testorder.source-root` | String | Auto-detected | Main source directory (Java source) |
| `testSourceRoot` | `testorder.test-source-root` | String | Auto-detected | Test source directory (test classes) |

#### Change Detection

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `changeMode` | `testorder.change-mode` | String | `auto` | Change detection mode. Valid values: `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit`. See [Change Detection](#change-detection-modes) for details. |
| `changedClasses` | `testorder.changed-classes` | String | None | Comma-separated fully qualified class names (for `explicit` mode only) |

#### Instrumentation & Analysis

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `includePackages` | `testorder.include-packages` | String | None | Comma-separated additional package prefixes to instrument |
| `filterByGroupId` | `testorder.filter-by-group-id` | Boolean | true | Use project groupId if no source packages detected |
| `instrumentationMode` | `testorder.instrumentation-mode` | String | `FULL` | Instrumentation mode: `FULL` or `SMART` |

#### Scoring & Optimization

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `weightsFile` | `testorder.weights-file` | String | None | Override scoring weights from properties file |

#### Debugging

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `verboseFile` | `testorder.verbose-file` | String | None | Enable verbose agent logging to file |

#### Method-Level Ordering

| Parameter | Property | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `methodOrderingEnabled` | `testorder.method-ordering-enabled` | Boolean | false | Enable method-level test ordering (experimental) |

---

### Change Detection Modes

The `changeMode` parameter controls how test-order detects which classes have changed.

#### `auto` (Default)
- Tries git-based detection first (if in git repository)
- Falls back to hash-based detection if git unavailable
- Automatically switches between methods based on availability

**Use case**: Works for most projects without configuration

**Example**:
```bash
mvn test-order:combined test -Dtestorder.change-mode=auto
```

---

#### `since-last-commit`
- Detects files modified since last git commit
- Requires: Git repository and `git` command available
- Most accurate for CI/CD integration

**Use case**: Pre-commit checks, pull request testing

**Example**:
```bash
mvn test-order:combined test -Dtestorder.change-mode=since-last-commit
```

---

#### `since-last-run`
- Detects changes since last test execution
- Uses timestamp from `.test-order-state` file
- Works without git

**Use case**: Continuous development, offline mode

**Example**:
```bash
mvn test-order:combined test -Dtestorder.change-mode=since-last-run
```

---

#### `uncommitted`
- Detects files with uncommitted changes (git only)
- Useful for detecting both staged and unstaged changes
- Requires: Git repository

**Use case**: Pre-push verification, comprehensive testing before commit

**Example**:
```bash
mvn test-order:combined test -Dtestorder.change-mode=uncommitted
```

---

#### `explicit`
- Only tests specified in `changedClasses` parameter
- Useful for: explicit CI configuration, manual override
- Requires: `changedClasses` parameter

**Use case**: CI/CD pipelines with computed changed classes

**Example**:
```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.UserService,com.example.PaymentProcessor
```

---

## Parameter Validation

All test-order Maven parameters are validated at startup to catch configuration errors early. This section describes validation rules and error messages you may encounter.

### Validation Overview

Parameters are validated in three phases:

1. **Base Class Validation** (AbstractTestOrderMojo):
   - `changeMode` - must be valid enum value
   - `changedClasses` - required when using explicit mode
   - `weightsFile` - must exist if specified

2. **Combined Mojo Validation** (CombinedMojo):
   - `instrumentationMode` - must be FULL or SMART
   - `selectTopN` - must be >= 0
   - `selectRandomM` - must be >= 0
   - Warning if both selections are 0 (no tests will run)
   - `optimizeEvery` - must be >= 0

3. **Coverage Mojo Validation** (CoverageMojo):
   - `threshold` - must be 0-100
   - `outputDirectory` - auto-created if missing
   - `outputFormat` - must be markdown or json

### Change Mode Validation

**Valid values**: `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit`

```bash
# ✅ Valid
mvn test-order:combined test -Dtestorder.change-mode=auto

# ✅ Valid (case insensitive)
mvn test-order:combined test -Dtestorder.change-mode=SINCE-LAST-RUN

# ❌ Invalid - will throw error
mvn test-order:combined test -Dtestorder.change-mode=invalid
# Error: [test-order] Invalid changeMode 'invalid'. Valid values are: auto, since-last-run, since-last-commit, uncommitted, explicit
```

### Explicit Mode Requirements

When using `explicit` mode, you MUST provide the `changedClasses` parameter:

```bash
# ❌ Invalid - missing changedClasses
mvn test-order:combined test -Dtestorder.change-mode=explicit

# ✅ Valid - changedClasses provided
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.UserService,com.example.PaymentService
```

### Instrumentation Mode Validation

**Valid values**: `FULL`, `SMART`

```bash
# ✅ Valid
mvn test-order:combined test -Dtestorder.instrumentation-mode=FULL

# ✅ Valid (case insensitive)
mvn test-order:combined test -Dtestorder.instrumentation-mode=smart

# ❌ Invalid - will throw error
mvn test-order:combined test -Dtestorder.instrumentation-mode=partial
# Error: [test-order] Invalid instrumentationMode 'partial'. Valid values are: FULL, SMART
```

### Selection Parameter Validation

Both `selectTopN` and `selectRandomM` must be non-negative:

```bash
# ✅ Valid
mvn test-order:combined test -Dtestorder.select-top-n=20 -Dtestorder.select-random-m=10

# ⚠️ Warning - both are 0, no tests will be selected
mvn test-order:combined test -Dtestorder.select-top-n=0 -Dtestorder.select-random-m=0
# Warning: [test-order] Both selectTopN and selectRandomM are 0 — no tests will be selected. Set selectTopN to at least 1.

# ❌ Invalid - negative value
mvn test-order:combined test -Dtestorder.select-top-n=-5
# Error: [test-order] selectTopN cannot be negative: -5
```

### Weights File Validation

If `weightsFile` is specified, it must exist:

```bash
# ✅ Valid - file exists
mvn test-order:combined test -Dtestorder.weights-file=./weights.txt

# ❌ Invalid - file doesn't exist
mvn test-order:combined test -Dtestorder.weights-file=./nonexistent.txt
# Error: [test-order] weightsFile './nonexistent.txt' does not exist
```

### Coverage Threshold Validation

Coverage thresholds must be between 0 and 100:

```bash
# ✅ Valid
mvn test-order:coverage -Dtestorder.threshold-percent=50

# ❌ Invalid - out of range
mvn test-order:coverage -Dtestorder.threshold-percent=150
# Error: [test-order] threshold must be between 0 and 100, got 150
```

### Common Validation Errors

| Error | Cause | Solution |
|-------|-------|----------|
| `Invalid changeMode 'xxx'` | Typo in mode value | Use: auto, since-last-run, since-last-commit, uncommitted, explicit |
| `changedClasses is required when using explicit mode` | Missing parameter | Add `-Dtestorder.changed-classes=...` |
| `selectTopN cannot be negative: -5` | Negative value | Use >= 0 |
| `Both selectTopN and selectRandomM are 0` | No tests selected | Set selectTopN to at least 1 |
| `weightsFile './file.txt' does not exist` | File not found | Check path and ensure file exists |
| `Invalid instrumentationMode 'HYBRID'` | Invalid enum value | Use FULL or SMART |
| `threshold must be between 0 and 100` | Out of range | Specify 0-100 |

---

## Common Use Cases

### Use Case 1: Local Development (Fast Feedback)

Goal: Run only the most relevant tests quickly.

```bash
# Daily development - run top tests + random diverse tests
mvn test-order:combined test

# If combined fails, run remaining tests
mvn test-order:run-remaining test
```

**Why this works**:
- `combined` selects only 20 top-scored + 10 random tests (~30 tests)
- Typically 10-20x faster than full test suite
- High probability of catching regressions (top-scored tests)
- Random tests provide diversity

**Configuration** (in `pom.xml` for persistent settings):
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <configuration>
    <selectTopN>20</selectTopN>
    <selectRandomM>10</selectRandomM>
    <filterByGroupId>true</filterByGroupId>
  </configuration>
</plugin>
```

---

### Use Case 2: Pull Request Testing (Comprehensive)

Goal: Run all tests affected by PR changes, maximize coverage.

```bash
# Detect PR changes (assuming conventional commit messages)
mvn test-order:combined test -Dtestorder.change-mode=uncommitted

# Or explicit: specify changed classes
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.UserService

# Run remaining tests for comprehensive coverage
mvn test-order:run-remaining test
```

**Why this works**:
- Focuses on changed classes and their dependencies
- Runs full test suite in second command (comprehensive)
- Parallelizes: first command finds regressions fast, second ensures nothing breaks

---

### Use Case 3: CI/CD Pipeline (Structured)

Goal: Efficient, reproducible testing with multiple stages.

```bash
# Stage 1: Quick smoke tests (5-10 tests)
mvn test-order:combined test \
  -Dtestorder.select-top-n=10 \
  -Dtestorder.select-random-m=0 \
  -Dtestorder.change-mode=since-last-commit

# Stage 2: Comprehensive testing (if stage 1 passes)
mvn test-order:run-remaining test

# Stage 3: Coverage analysis
mvn test-order:coverage \
  -Dcoverage.threshold=70 \
  -Dcoverage.outputFormat=json
```

**YAML Example (GitHub Actions)**:
```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0  # Full history for git-based change detection
      
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      
      # Smoke tests
      - name: Smoke tests (fast)
        run: mvn test-order:combined test -Dtestorder.select-top-n=10 -Dtestorder.change-mode=since-last-commit
      
      # Full test suite
      - name: Full tests
        if: success()
        run: mvn test-order:run-remaining test
      
      # Coverage analysis
      - name: Coverage analysis
        if: always()
        run: mvn test-order:coverage -Dcoverage.threshold=70
```

---

### Use Case 4: Debugging Test Ordering

Goal: Understand why tests run in particular order.

```bash
# Show test order with scores
mvn test-order:show-order

# Show order for changed classes only
mvn test-order:show-order -Dtestorder.change-mode=since-last-commit

# Dump full dependency index
mvn test-order:dump

# Enable verbose logging
mvn test-order:combined test -Dtestorder.verbose-file=verbose.log
tail -f verbose.log
```

---

### Use Case 5: Multi-Module Projects

Goal: Run tests with proper cross-module dependency awareness.

```bash
# Prepare all modules
mvn test-order:prepare

# Combined mode respects module dependencies
mvn test-order:combined test

# Coverage across all modules
mvn test-order:coverage -Dcoverage.includeModules=core,api,impl
```

---

## Property Naming Convention

test-order uses Maven properties for configuration. Two naming styles are supported:

### Canonical Names (Recommended)

Use hyphenated names with `testorder.` prefix:

```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=since-last-commit \
  -Dtestorder.select-top-n=30 \
  -Dtestorder.filter-by-group-id=true
```

### Legacy Names

Some older properties use alternate names. Both work:

```bash
# Both are equivalent:
-Dtestorder.index=/path/to/index.lz4
-Dtestorder.change-mode=auto
```

**Canonical to Legacy Mapping**:
| Canonical | Legacy | Notes |
|-----------|--------|-------|
| `testorder.index` | `testorder.index` | No alias |
| `testorder.state-file` | `testorder.state-file` | No alias |
| `testorder.change-mode` | `testorder.change-mode` | No alias |
| `testorder.filter-by-group-id` | `testorder.filter-by-group-id` | No alias |
| `testorder.instrumentation-mode` | `testorder.instrumentation-mode` | FULL or SMART |

**Recommendation**: Use canonical hyphenated names for new code.

---

## Quick Start Guide (Default Behavior)

### Zero Configuration (Recommended for 80% of Projects)

The test-order defaults are optimized for typical Maven projects. You likely need **zero configuration**:

```bash
# First run: initialize + run test selection
mvn test-order:combined test

# Subsequent runs: automatically selective
mvn test-order:combined test

# When you're confident: run all tests
mvn test
```

**What happens with defaults**:
- **Change Mode**: `auto` - detects changes automatically
- **Selection**: Top 20 tests + 10 random tests
- **Instrumentation**: FULL bytecode analysis
- **Dependencies**: Stored in `target/test-order-deps`
- **State**: Cached in `.test-order-state`

### When to Customize

#### Local Development (Faster Feedback)

Use `selectTopN=10` for even faster feedback:

```bash
mvn test-order:combined test -Dtestorder.select-top-n=10
```

**Trade-off**: Runs fewer tests (10 vs 20), misses some regressions, but 20% faster

#### CI/CD (Safety Over Speed)

Run full test suite or increase random selection:

```bash
# Option 1: Full suite (safest)
mvn test

# Option 2: More selective tests
mvn test-order:combined test -Dtestorder.select-top-n=50 -Dtestorder.select-random-m=20
```

#### Branch Merges (Different Change Detection)

When you want to run tests only for the branch's changes:

```bash
# Since last commit on main
mvn test-order:combined test -Dtestorder.change-mode=since-last-commit

# Only uncommitted changes
mvn test-order:combined test -Dtestorder.change-mode=uncommitted
```

#### Specific Classes Changed (Explicit Mode)

When you know exactly which classes were modified:

```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.PaymentService,com.example.OrderProcessor
```

### Default Values Reference

| Parameter | Default | Good For | Consider Changing |
|-----------|---------|----------|-------------------|
| `changeMode` | `auto` | ✅ Most projects | Branches, explicit testing |
| `selectTopN` | `20` | ✅ Most projects | Faster dev (10), safer CI (50) |
| `selectRandomM` | `10` | ✅ Most projects | High coverage needs (20) |
| `instrumentationMode` | `FULL` | ✅ Most projects | Large codebases (SMART) |
| `stateFile` | `.test-order-state` | ✅ Works everywhere | Custom storage paths |
| `hashFile` | `.test-order-hashes.lz4` | ✅ Works everywhere | Custom storage paths |
| `depsDirectory` | `target/test-order-deps` | ✅ Ignored in .git* | Never change |

### How Defaults Cover Common Scenarios

| Scenario | Default Behavior | Result |
|----------|------------------|--------|
| First run | Learn mode (builds index, runs all tests) | No tests skipped initially |
| Regular dev | Selective (top 20 + 10 random) | ~30 tests run (~80% coverage) |
| Test fails | Re-run full suite | All tests included |
| Code change | Auto-detects changed files | Tests for changed code prioritized |
| Branch merge | Auto-detects merge commits | All affected code tested |

**Key Insight**: Defaults are tuned so 80% of projects work without configuration. The 20% exception cases get custom parameters.

---

## Error Messages & Troubleshooting

### Error: "No dependency index found"

**Cause**: Index file doesn't exist.

**Solution**:
```bash
# Initialize index (learn mode)
mvn test-order:combined test

# Or snapshot without running tests
mvn test-order:snapshot test
```

---

### Error: "Change mode 'INVALID' not recognized"

**Cause**: Invalid `changeMode` value.

**Solution**: Use valid values: `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit`

```bash
# Correct usage
mvn test-order:combined test -Dtestorder.change-mode=since-last-commit
```

---

### Error: "Output format 'invalid' not recognized"

**Cause**: Invalid `outputFormat` value in coverage goal.

**Solution**: Use valid values: `comprehensive`, `markdown`, `json`

```bash
# Correct usage
mvn test-order:coverage -Dcoverage.outputFormat=json
```

---

### Error: "Explicit mode but no changed classes specified"

**Cause**: Used `changeMode=explicit` without `changedClasses`.

**Solution**: Provide changed class names:

```bash
mvn test-order:combined test \
  -Dtestorder.change-mode=explicit \
  -Dtestorder.changed-classes=com.example.A,com.example.B
```

---

### Slow Test Execution (Index Not Used)

**Symptoms**: Tests run in default order, test-order doesn't seem active.

**Causes & Solutions**:
1. **Index doesn't exist**: Run `mvn test-order:prepare` first
2. **Wrong goal used**: Use `test-order:combined` instead of just `test`
3. **Instrumentation failed**: Check `verbose-file` for errors
4. **Package not instrumented**: Add to `includePackages` if needed

**Debugging**:
```bash
mvn test-order:combined test -Dtestorder.verbose-file=verbose.log
cat verbose.log | grep -E "ERROR|WARN|instrumented"
```

---

### Coverage Report Not Generated

**Cause**: Coverage reporting configured incorrectly.

**Solution**:
1. Verify JaCoCo coverage data exists: `target/site/jacoco/`
2. Run with verbose output:
   ```bash
   mvn test-order:coverage -X
   ```
3. Check output directory permissions:
   ```bash
   mvn test-order:coverage -Dcoverage.outputDir=/tmp/reports
   ```

---

## Property File Configuration

Instead of command-line properties, create `.mvn/extensions.xml` or `pom.xml` configuration:

**In pom.xml**:
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>${testorder.version}</version>
  <configuration>
    <changeMode>since-last-commit</changeMode>
    <topN>30</topN>
    <randomM>10</randomM>
    <filterByGroupId>true</filterByGroupId>
    <instrumentationMode>FULL</instrumentationMode>
  </configuration>
</plugin>

<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-coverage-mojo</artifactId>
  <version>${testorder.version}</version>
  <configuration>
    <threshold>60</threshold>
    <outputFormat>comprehensive</outputFormat>
    <outputDir>${project.build.directory}/coverage-reports</outputDir>
  </configuration>
</plugin>
```

---

## Next Steps

- See [ARCHITECTURE.md](ARCHITECTURE.md) for system design
- See [ADVANCED_USAGE.md](ADVANCED_USAGE.md) for custom components
- See [PERFORMANCE.md](PERFORMANCE.md) for tuning guidelines

