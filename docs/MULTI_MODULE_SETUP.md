# Multi-Module Projects with test-order Plugin

**Complete Guide to Setting Up Test Ordering in Maven & Gradle Multi-Module Builds**

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Maven Setup](#maven-setup)
4. [Gradle Setup](#gradle-setup)
5. [How Multi-Module Reordering Works](#how-multi-module-reordering-works)
6. [PIT Mutation Testing in Multi-Module Builds](#pit-mutation-testing-in-multi-module-builds)
7. [Parallel Execution](#parallel-execution)
8. [Best Practices](#best-practices)
9. [Troubleshooting](#troubleshooting)

---

## Overview

The test-order plugin brings **intelligent test reordering** to multi-module projects by:

- **Sharing a dependency index** across all modules (at the root project)
- **Maintaining per-module state** to prevent test isolation issues in parallel builds
- **Aggregating test results** for global test optimization
- **Supporting hierarchical module structures** (modules with sub-modules)

### Key Benefits for Multi-Module Projects

✅ **Faster CI/CD**: Run changed-test modules first, defer unaffected modules  
✅ **Parallel Safety**: Each module has isolated state; no test interference  
✅ **Scalability**: Works with 2 modules or 200 modules  
✅ **Dashboard**: Unified visualization of test execution across all modules

---

## Architecture

### File Structure

```
project-root/
├── .test-order/                    ← Shared (root level)
│   ├── test-dependencies.lz4       ← Index (ALL modules' dependencies)
│   ├── state.lz4                   ← Shared state (run history, weights, failures)
│   ├── deps/                       ← Shared deps directory
│   ├── hashes/
│   │   ├── com.app-module-a-hashes.lz4
│   │   ├── com.app-module-a-test-hashes.lz4
│   │   ├── com.app-module-b-hashes.lz4
│   │   └── com.app-module-b-test-hashes.lz4
│   └── (other files...)
│
├── module-a/
│   └── pom.xml (or build.gradle)
│
├── module-b/
│   └── pom.xml (or build.gradle)
│
├── module-c/                       ← Sub-module
│   └── pom.xml (or build.gradle)
│
└── pom.xml (root) or settings.gradle

Legend:
  🔴 RED   = Shared (one copy for all modules)
  🟡 YELLOW = Per-module (each module has its own, stored in hashes/)
```

### Why This Design?

**Shared Index**: All modules contribute `.deps` files during learn mode. After `testOrderAggregate`, you have a **single dependency graph** showing which tests cover which classes across **all modules**. This enables:
- Running only affected test modules
- Detecting inter-module dependencies
- Global test prioritization

**Shared State**: The `state.lz4` file is shared at the reactor root level, providing a unified view of run history, scoring weights, and failure data across all modules.

**Per-Module Hashes**: Source/test file hashes are stored per-module (using `<groupId>-<artifactId>` prefixes) to enable module-specific change detection without interference between modules.

---

## Maven Setup

### 1. Root `pom.xml`

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example.app</groupId>
    <artifactId>app-root</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>module-a</module>
        <module>module-b</module>
        <module>module-c</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <test-order.version>0.0.1-SNAPSHOT</test-order.version>
    </properties>

    <!-- Plugin Management (inherited by all modules) -->
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>${test-order.version}</version>
                <extensions>true</extensions>  <!-- required: registers the lifecycle participant that writes the index -->
                <configuration>
                    <!-- All modules inherit this config -->
                    <instrumentationMode>CLASS</instrumentationMode>
                    <changeMode>uncommitted</changeMode>
                    <scoreNewTest>100</scoreNewTest>
                </configuration>
            </plugin>
        </plugins>
    </pluginManagement>

    <build>
        <plugins>
            <!-- Apply plugin to all modules automatically -->
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2. Module `pom.xml`

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example.app</groupId>
        <artifactId>app-root</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>module-a</artifactId>
    <name>Module A - Core Logic</name>

    <dependencies>
        <!-- ... your dependencies ... -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Inherits configuration from root pluginManagement -->
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
            </plugin>

            <!-- Standard Maven plugins -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <includes>
                        <include>**/*Test.java</include>
                        <include>**/*Tests.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3. Build Commands

```bash
# Learn mode: collect dependencies from all modules
mvn clean install -Dtestorder.mode=learn

# After collecting deps, aggregate into shared index
mvn test-order:aggregate

# Order mode: run tests in optimized order
mvn clean test

# View test execution plan (before running)
mvn test-order:show

# Generate dashboard
mvn test-order:dashboard

# Diagnose setup issues
mvn test-order:diagnose

# Clean all test-order files (for starting over)
mvn test-order:clean
```

### 4. Module-Specific Overrides

Override configuration in individual modules if needed:

```xml
<!-- module-b/pom.xml -->
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <changeMode>since-last-commit</changeMode>
        <scoreSpeed>150</scoreSpeed>
        <!-- Override from root config for this module only -->
    </configuration>
</plugin>
```

---

## Gradle Setup

### 1. Root `settings.gradle`

```gradle
pluginManagement {
    repositories {
        maven {
            url 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        gradlePluginPortal()
    }
}

rootProject.name = 'app-root'

includeBuild 'module-a'
includeBuild 'module-b'
includeBuild 'module-c'
// or: include 'module-a', 'module-b', 'module-c' (if single build)
```

### 2. Root `build.gradle`

```gradle
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT' apply false
}

// Shared configuration for all subprojects
subprojects {
    apply plugin: 'java'
    apply plugin: 'me.bechberger.test-order'

    group = 'com.example.app'
    version = '1.0.0'

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation 'org.junit.jupiter:junit-jupiter:5.9.2'
    }

    test {
        useJUnitPlatform()
    }

    // Shared test-order configuration
    testOrder {
        mode = 'auto'
        instrumentationMode = 'CLASS'
        changeMode = 'uncommitted'
        scoreNewTest = 100
        autoLearnRunThreshold = 10
        autoLearnDiffThreshold = 0
    }
}
```

### 3. Module `build.gradle`

```gradle
plugins {
    id 'java'
}

dependencies {
    // ... your dependencies ...
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

test {
    useJUnitPlatform()
}

// Optional: override global testOrder config for this module
testOrder {
    changeMode = 'since-last-commit'
    scoreSpeed = 150
}
```

### 4. Build Commands

```bash
# Learn mode: collect from all subprojects
./gradlew clean build -Dtestorder.mode=learn

# Aggregate deps into shared index
./gradlew testOrderAggregate

# Order mode: optimized execution
./gradlew clean test

# Show predicted order and scores (unified view)
./gradlew testOrderShow

# Legacy aliases (deprecated): testOrderShowOrder, testOrderExplainOrder

# Generate dashboard
./gradlew testOrderDashboard

# Serve dashboard locally (opens in browser)
./gradlew testOrderServe

# Diagnose setup
./gradlew testOrderDiagnose

# Clean test-order artifacts
./gradlew testOrderClean
```

---

## Parallel Execution

### Maven: Parallel Module Builds

```bash
# Run modules in parallel (up to N threads)
mvn clean test -T 1C
# -T 1C = 1 thread per CPU core

# With test-order optimization
mvn clean test -T 1C -DfailIfNoTests=false

# Note: test-order automatically handles state isolation
# (each module locks only its own state.lz4)
```

### Gradle: Parallel Task Execution

```bash
# Enable parallel in gradle.properties
org.gradle.parallel=true
org.gradle.workers.max=<number of cores>

# Or via command line
./gradlew test --parallel --max-workers=4

# Works seamlessly with test-order (per-module isolation)
```

### Important: Hash File Locking

When running in parallel, hash file access is coordinated:

```
Module A: locks .test-order/hashes/com.app-module-a-hashes.lz4
Module B: locks .test-order/hashes/com.app-module-b-hashes.lz4
Module C: locks .test-order/hashes/com.app-module-c-hashes.lz4
         ↓
         No conflicts! (Different hash files per module)
         ↓
Module A succeeds
Module B succeeds
Module C succeeds
```

The shared index (test-dependencies.lz4) is only written during aggregation (after all modules complete).

---

## How Multi-Module Reordering Works

### The Dependency Index: One File, All Modules

The dependency index (`test-dependencies.lz4`) is the heart of the reordering system. In a multi-module build, **all modules share a single index** stored at the reactor root (`.test-order/test-dependencies.lz4`). This is intentional: cross-module dependencies are common (module A's tests cover classes in module B), and a shared index captures those edges.

#### How the index is built

During **learn mode**, each module independently instruments its production classes using offline bytecode instrumentation. When the test JVM runs, it records which test class accessed which production class via the `IndexCollectorServer` — every test method → production class edge is written to a per-module `.deps` file under `.test-order/deps/`.

```
Learn run:
  module-a tests run → write .test-order/deps/com.app-module-a-*.deps
  module-b tests run → write .test-order/deps/com.app-module-b-*.deps
                                    ↓
  mvn test-order:aggregate   ← merges all .deps files into test-dependencies.lz4
```

> In Maven the `CollectorLifecycleParticipant` (registered automatically via `extensions.xml`) calls `aggregate` at session end, so an explicit `test-order:aggregate` goal is usually not needed. In Gradle the `testOrderAggregateAll` task combines all subproject `.deps` outputs.

#### What the index stores

Each entry in the index maps a **test class** to the set of **production class FQCNs** it covers. A single test class may cover hundreds of classes across modules. This is what enables cross-module change detection:

```
com.example.CartTest → [com.example.Cart, com.example.Inventory,
                         com.pricing.PriceCalculator,   ← from module-b!
                         com.db.OrderRepository]         ← from module-c!
```

When `PriceCalculator` changes, `CartTest` is boosted even though it lives in a different module.

### Per-Module Change Detection

Although the index is shared, **change detection runs per-module**. Each module tracks its own source file hashes in `.test-order/hashes/<groupId>-<artifactId>-hashes.lz4`. When you run order mode on module A, the plugin:

1. Reads the **local** hash file for module A to detect changed classes
2. Looks up those changed classes in the **shared** index
3. Finds and scores every test that covers those changed classes — including tests in module B or C

This is how a one-line change in `module-a/src/main/java/…/Cart.java` surfaces as a score boost for `CartTest` in module C during a full reactor build.

### Reactor Build Flow

In a normal `mvn test` run across all modules:

```
1. CollectorLifecycleParticipant.afterProjectsRead()
   → allocates a shared ClassIdMap (reactor-wide class ID registry)
   → scores modules by affected test count; optionally reorders reactor

2. For each module in reactor order:
   a. test-order:prepare fires (bound to process-test-classes)
      → reads per-module hash file, computes changed classes
      → looks up shared index → builds score-ordered test list
      → writes JUnit platform properties for PriorityClassOrderer

   b. Maven Surefire runs tests in plugin-specified order
      → TelemetryListener records durations and outcomes to .part files

3. CollectorLifecycleParticipant.afterSessionEnd()
   → merges all .part files into shared state.lz4
   → increments runsSinceLearn, updates failure decay
   → triggers auto-aggregate if enough new .deps files accumulated
```

### The `reactor-order` Goal

`mvn test-order:reactor-order` is a **diagnostic / planning goal** for understanding module prioritization before committing to a full build.

**What it does:**
- Computes a per-module urgency score based on affected test count, maximum test score, and sum of test scores
- Ranks modules from most urgent (many high-score tests) to least urgent (no affected tests)
- Suggests a `-pl` argument to run affected modules first in a two-step build

```bash
# See urgency ranking + suggested -pl
mvn test-order:reactor-order

# Machine-readable: just the -pl argument
PL=$(mvn -q test-order:reactor-order -Dtestorder.reactor.suggest=true)
mvn test $PL -am           # run affected modules first
mvn test --resume-from=…   # run remaining modules
```

**Example output:**
```
║  Changed classes: 3
║  Modules:         5
  #1  payment-service           max=210  sum= 4500  (8/42 affected)
       → PaymentProcessorTest
       → RefundWorkflowTest
  #2  inventory-service         max=120  sum= 2100  (3/31 affected)
  #3  notification-service      max=  0  sum=    0  (no affected tests)
  ...

[test-order] Suggested fast-feedback command (affected modules first):
  mvn test -pl payment-service,inventory-service -am
[test-order] Then run remaining modules:
  mvn test -pl notification-service,reporting-service,gateway
```

**Important constraints:**
- Requires an existing dependency index (run learn mode first)
- `auto` and `since-last-run` change modes are downgraded to `uncommitted` in this aggregator context — per-module hash files are not accessible from the reactor root
- Module ordering respects Maven's dependency DAG: the goal only reorders **topologically independent** modules

### Automatic Reactor Reordering

The `CollectorLifecycleParticipant` lifecycle extension (automatically active when the plugin is on the classpath) can reorder Maven reactor modules at build startup so modules with the most affected tests run first:

```bash
# Enable reactor reordering (reorder modules by affected test count, but still run all)
mvn test -Dtestorder.reactorReorder=true

# Reorder AND skip modules with no affected tests (run only top N)
mvn test -Dtestorder.reactorReorder=true -DtestorderReactorTopN=5

# Dry-run: print the planned reorder without actually reordering
mvn test -Dtestorder.reactorReorder=true -Dtestorder.reactorReorder.dryRun=true
```

| Property | Default | Notes |
|---|---|---|
| `testorder.reactorReorder` | `false` | Enable lifecycle-level reactor module reordering |
| `testorder.reactorTopN` | unset | Limit execution to the top N modules by affected test count; remaining modules get `skipTests=true` |
| `testorder.reactorReorder.dryRun` | `false` | Print reorder plan without modifying the reactor |

> Requires at least one prior learn run so the plugin has dependency data to score modules against the current change set.

---

## PIT Mutation Testing in Multi-Module Builds

### What `analyze-mutations` does

`mvn test-order:analyze-mutations` (Maven) / `./gradlew testOrderAnalyzeMutations` (Gradle) runs [PIT](https://pitest.org/) mutation testing on the production classes covered by the dependency index, then feeds the per-test kill rates back into the shared `state.lz4` for use in future scoring runs.

**Purpose:** Tests that reliably kill mutants in the classes they cover are stronger indicators of code correctness than tests that merely execute the code. The `killRateBonus` scoring weight (`scoreKillRateBonus`) uses these rates to boost high-kill tests.

**Designed for nightly / weekly CI** — not every commit. PIT is slow (it runs tests N times, one per mutant). A typical project with 1,000 tests might take 20–60 minutes.

### How it works

```
1. Load dependency index → extract the set of all indexed production classes
2. Build PIT target-class glob from those classes (or from -Dtestorder.mutations.targetClasses)
3. Invoke PIT programmatically (via EntryPoint reflection — no separate process)
4. PIT mutates each production class, runs the test suite, records which test kills which mutant
5. Parse PIT XML report → compute per-test kill rate = mutants_killed / mutants_tested_by_this_test
6. Write kill rates to state.lz4 (keyed by test class FQCN)
7. Write test-mutation-results.json report
```

### In Multi-Module Projects

`analyze-mutations` is an **aggregator goal** — it runs once from the reactor root and sees the entire dependency index. However, there is an important constraint: **PIT must be able to find all production class files and test class files** from the path it is invoked.

For multi-module builds, the simplest approach is to run from the root after a full build:

```bash
# 1. Full build with tests (ensures all class files are compiled)
mvn clean install -DskipTests

# 2. Run mutation analysis (aggregator goal, runs at root)
mvn test-order:analyze-mutations

# 3. Enable kill-rate bonus in scoring
mvn test -DscoreKillRateBonus=50
```

**Per-module scope:** Use `-Dtestorder.mutations.targetClasses` to limit mutation to one module:

```bash
mvn test-order:analyze-mutations \
  -Dtestorder.mutations.targetClasses="com.example.payment.*"
```

**Time budget:** For large projects, limit PIT's runtime:

```bash
# Stop after 10 minutes (useful for CI time limits)
mvn test-order:analyze-mutations -Dtestorder.mutations.timeBudget=600
```

### Kill Rate in Scoring

Once kill rates are stored in `state.lz4`, every subsequent order/select run incorporates them automatically when `scoreKillRateBonus > 0`:

```xml
<!-- pom.xml -->
<configuration>
    <scoreKillRateBonus>50</scoreKillRateBonus>  <!-- add up to 50 points for high kill rate -->
</configuration>
```

Or from the command line:

```bash
mvn test -DscoreKillRateBonus=50
```

Kill rates decay over time as the code changes — after a re-run of `analyze-mutations`, the rates are refreshed. The state file stores them per-test and they persist across builds until explicitly cleared.

### PIT Prerequisites

PIT must be on the classpath. Add the dependency to your root POM (test scope, since it's only needed for mutation runs):

```xml
<dependency>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-entry</artifactId>
    <version>1.16.1</version>  <!-- use latest stable -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-junit5-plugin</artifactId>
    <version>1.2.1</version>
    <scope>test</scope>
</dependency>
```

If PIT is not on the classpath, `analyze-mutations` fails with a clear error message listing the missing class.

---

## Best Practices

### 1. **Initial Setup Checklist**

- [ ] Root project has test-order plugin configured
- [ ] All subprojects inherit via `pluginManagement` (Maven) or `subprojects` block (Gradle)
- [ ] First run: Use `learn` mode to collect dependencies
  ```bash
  # Maven
  mvn clean install -Dtestorder.mode=learn
  
  # Gradle
  ./gradlew clean build -Dtestorder.mode=learn
  ```
- [ ] Aggregate dependencies
  ```bash
  # Maven
  mvn test-order:aggregate
  
  # Gradle
  ./gradlew testOrderAggregate
  ```
- [ ] Verify index was created
  ```bash
  ls -lh .test-order/test-dependencies.lz4
  ```

### 2. **Daily Workflow**

```bash
# Option 1: Auto mode (recommended)
mvn clean test          # Maven auto-learns when needed
./gradlew clean test    # Gradle auto-learns when needed

# Option 2: Explicit aggregation (for CI/CD)
mvn clean test -Dtestorder.mode=learn
mvn test-order:aggregate
mvn test-order:prepare -Dtestorder.mode=order

# Option 3: Skip reordering when debugging specific tests
mvn test -Dtest=MyTest -Dtestorder.skip=true
./gradlew test --tests MyTest -Dtestorder.skip=true
```

### 3. **CI/CD Integration**

**GitHub Actions Example**:
```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0  # Full history for change detection
      
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      
      - name: Learn (first run or after major changes)
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        run: mvn clean install -Dtestorder.mode=learn
      
      - name: Aggregate
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        run: mvn test-order:aggregate
      
      - name: Test (order mode)
        run: mvn clean test
      
      - name: Export metrics
        run: ls -lh target/test-order-metrics.json && cat target/test-order-metrics.json
      
      - name: Upload to artifact
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-metrics
          path: target/test-order-metrics.json
```

### 4. **Handling Flaky Tests**

```bash
# Run specific test without ordering (to isolate flakiness)
mvn test -Dtest=FlakyTest -Dtestorder.skip=true

# Or exclude from ordering and run separately
mvn clean test -Dtestorder.skip=true -Dtest=FlakyTest
mvn clean test -DexcludedGroups=flaky

# After fixing, re-learn
mvn clean install -Dtestorder.mode=learn && mvn test-order:aggregate
```

### 5. **Module Dependencies**

If Module B depends on Module A's tests, ensure correct execution order:

```
module-a/pom.xml:
  <module>module-b</module>

module-b/pom.xml:
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>module-a</artifactId>
    <version>${project.version}</version>
  </dependency>
```

Test-order detects this during learn mode and schedules Module A tests first.

---

## Troubleshooting

### Problem: Index is empty / No tests detected

**Symptom**: `test-dependencies.lz4` exists but is very small (< 1 KB)

**Causes**:
1. Tests weren't collected in learn mode
2. Aggregation didn't run
3. Tests aren't named `*Test.java` or `*Tests.java`

**Solution**:
```bash
# Maven
mvn clean install -Dtestorder.mode=learn -X 2>&1 | grep -i "deps\|test"
mvn test-order:aggregate
mvn test-order:diagnose

# Gradle
./gradlew clean build -Dtestorder.mode=learn --debug 2>&1 | grep -i "deps\|test"
./gradlew testOrderAggregate
./gradlew testOrderDiagnose
```

### Problem: Parallel builds deadlock

**Symptom**: Tests hang or timeout during parallel execution

**Causes**:
1. Hash file lock timeout (> 30 seconds)
2. State file corrupted from concurrent access
3. Network filesystem locks are slow

**Solution**:
```bash
# Reduce stale-lock threshold (default 120 minutes); lock files older than this are removed
mvn test -Dtestorder.lock.stale.minutes=5

# For Gradle
./gradlew test -Dtestorder.lock.stale.minutes=5

# Disable parallel execution temporarily
mvn test -T 1  # Maven: serial
./gradlew test --max-workers=1  # Gradle: serial

# Check what's holding the lock
lsof | grep ".test-order"
```

### Problem: Module-specific state not isolated

**Symptom**: Running Module A tests affects Module B test scoring

**Causes**:
1. Custom path configuration overriding defaults
2. Plugin not detecting multi-module mode (single-module fallback paths in use)

**Solution**:
```bash
# In multi-module mode, there is ONE shared state file at the reactor root.
# Verify the shared state file is in the reactor root .test-order/
ls -la .test-order/state.lz4

# Check extension config
mvn test-order:diagnose  # Shows resolved paths

# Reset to defaults
rm -rf .test-order
mvn clean install -Dtestorder.mode=learn
```

### Problem: Changed detection misses cross-module impacts

**Symptom**: Changed Module A's code but Module B tests still run

**Expected**: Module B tests should run because they depend on Module A

**Solution**:
1. Learn mode must collect full dependency graph (all modules)
2. Index must be aggregated after all modules contribute
3. Use `testOrderDiagnose` to verify coverage

```bash
# Ensure full learn cycle
mvn clean install -Dtestorder.mode=learn
mvn test-order:aggregate
mvn test-order:show  # Verify Module B tests are included

# If still missing, check package structure
# test-order needs source root to auto-detect packages
```

---

## Dashboard & Metrics

### Generate Dashboard

```bash
# Maven
mvn test-order:dashboard
# Opens: target/test-order-dashboard/index.html

# Gradle
./gradlew testOrderDashboard
# Opens: build/test-order-dashboard/index.html
```

### View Metrics

```bash
# Maven
cat target/test-order-metrics.json | jq '.'

# Gradle
cat build/test-order-metrics.json | jq '.'

# Example output:
{
  "timestamp": "2026-04-29T10:30:00Z",
  "project": "app-root",
  "total_tests": 1240,
  "tests_selected": 340,
  "estimated_savings_seconds": 3200.5,
  "savings_percentage": 82.1,
  "modules": {
    "module-a": { "tests": 400, "selected": 120 },
    "module-b": { "tests": 550, "selected": 180 },
    "module-c": { "tests": 290, "selected": 40 }
  }
}
```

---

## Sample Multi-Module Project

See `samples/sample-multi/` for a complete working example:

```bash
cd samples/sample-multi
mvn clean install -Dtestorder.mode=learn
mvn test-order:aggregate
mvn clean test
mvn test-order:dashboard
```

---

## FAQ

**Q: Can I use test-order with nested modules (modules within modules)?**  
A: Yes! Hierarchy doesn't matter. Each module maintains its own state; shared index lives at root.

**Q: What happens if I delete a module?**  
A: Its hash files stay in `.test-order/hashes/`. Run `testOrderCompact` to clean up.

**Q: Can different modules use different changeMode values?**  
A: Yes, but not recommended. Set once at root; override only if needed.

**Q: How do I know which tests cover which classes?**  
A: `mvn test-order:dump` shows the dependency index in readable text format.

**Q: What if my CI system can't access the shared .test-order directory?**  
A: Use `-Dtestorder.skip=true` to disable reordering; tests will run in default order.

---

## Next Steps

1. Run the [Quick Start](#maven-setup)
2. Read [Architecture](#architecture) for deep understanding
3. Implement [Best Practices](#best-practices) in your CI/CD
4. Use `testOrderDiagnose` / `mvn test-order:diagnose` to validate setup
5. Monitor metrics and adjust scoring weights as needed

For questions or issues: See [Troubleshooting](#troubleshooting) or file a GitHub issue.
