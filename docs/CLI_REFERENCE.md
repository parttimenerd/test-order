# test-order CLI Reference Guide

Complete reference for all Maven plugin goals, Gradle plugin options, and configuration parameters.

---

## Table of Contents

1. [Maven Plugin Goals](#maven-plugin-goals)
2. [Configuration Parameters](#configuration-parameters)
3. [Common Use Cases](#common-use-cases)
4. [Property Naming Convention](#property-naming-convention)
5. [Error Messages & Troubleshooting](#error-messages--troubleshooting)

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

