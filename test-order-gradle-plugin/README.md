# test-order Gradle Plugin

Gradle plugin for JUnit test class priority ordering based on runtime dependency telemetry.
Runs the tests most likely affected by your latest code changes **first**, so failures surface faster.

**Requires Java 17+** and **Gradle 7.6+**. Compatible with JUnit 5 (Jupiter 5.x), JUnit 6 (Jupiter 6.x), and TestNG (7.x+).

On Java 24+ (JEP 472), test JVMs need native-access enablement for some runtime paths used by transitive dependencies. The plugin adds `--enable-native-access=ALL-UNNAMED` to Gradle `Test` tasks automatically.

## Quick start

### 1. Apply the plugin

<details>
<summary><b>Option A — plugins block (recommended)</b></summary>

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()       // needed while using SNAPSHOT versions
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'java'
    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
}

repositories {
    mavenLocal()   // needed while using SNAPSHOT versions
    mavenCentral()
}
```

</details>

<details>
<summary><b>Option B — init script (no build file changes)</b></summary>

Use this to apply test-order to **any** Gradle project without modifying its build files.
Save the init script anywhere (e.g. `test-order-init.gradle`), then pass it on the command line:

```bash
./gradlew test --init-script path/to/test-order-init.gradle -Dtestorder.mode=learn
```

The init script:

```groovy
// test-order-init.gradle
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath 'me.bechberger:test-order-gradle-plugin:0.1.0-SNAPSHOT'
    }
}

projectsLoaded {
    allprojects { project ->
        if (project.buildFile.absolutePath.contains('buildSrc')) return
        project.plugins.withId('java') {
            project.apply plugin: me.bechberger.testorder.gradle.TestOrderPlugin
        }
    }
}
```

</details>

### 2. Learn — build the dependency index

```bash
./gradlew test -Dtestorder.mode=learn
```

This attaches a Java agent that records which application classes each test exercises,
producing a `test-dependencies.lz4` index file. Commit it to version control.

A `.test-order/state.lz4` file is also created to track test durations and failure history.

### 3. Order — run tests with priority ordering

```bash
./gradlew test
```

Once a `test-dependencies.lz4` exists, the plugin automatically detects changed source files
and reorders tests so the most relevant ones run first. No extra flags needed (`auto` mode).

To force ordering mode explicitly:

```bash
./gradlew test -Dtestorder.mode=order
```

### 4. Disable

```bash
./gradlew test -Dtestorder.mode=skip
```

## Configuration

All settings are optional. The plugin works out of the box with sensible defaults.

```groovy
testOrder {
    // Mode: "auto" (default) | "learn" | "order" | "optimize" | "skip"
    mode = "auto"

    // Agent instrumentation depth:
    //   METHOD_ENTRY — only records method entries (fastest, least precise)
    //   FULL         — method entries + field accesses (default, good balance)
    //   FULL_METHOD  — like FULL but records per-method dependencies
    //   FULL_MEMBER  — most detailed: per-method deps + field accesses
    instrumentationMode = "FULL"

    // Comma-separated package prefixes to instrument.
    // Auto-detected from src/main/java if left empty.
    includePackages = ""

    // Change detection: "uncommitted" | "auto" | "since-last-run" | "since-last-commit" | "explicit"
    changeMode = "uncommitted"

    // For changeMode = "explicit": comma-separated FQCNs of changed classes
    changedClasses = ""

    // Paths (all relative to project dir by default)
    indexFile = file(".test-order/test-dependencies.lz4")
    stateFile = file(".test-order/state.lz4")
    depsDir   = layout.buildDirectory.dir("test-order-deps")

    // Scoring weights — see "Scoring system" below
    scoreNewTest          = 15
    scoreChangedTest      = 9
    scoreMaxFailure       = 5
    scoreSpeed            = 1
    scoreSpeedPenalty     = 1
    scoreDepOverlap       = 5
    scoreChangeComplexity = 2
    scoreStaticFieldBonus = 0
}
```

### Property overrides

Most settings can be overridden on the command line via `-D` (system property) or `-P` (Gradle project property).
Score weights (`scoreNewTest`, `scoreChangedTest`, etc.) must be set in the `testOrder { }` DSL block.

```bash
# System property
./gradlew test -Dtestorder.mode=learn

# Gradle property
./gradlew test -Ptestorder.mode=learn
```

## Tasks

| Task | Description |
|---|---|
| `testOrderDashboard` | Generate an interactive HTML dashboard from current index/state |
| `testOrderServe` | Serve dashboard over local HTTP (supports timed shutdown) |
| `testOrderShowOrder` | Display the predicted test execution order without running tests |
| `testOrderTieredSelect` | Run tier-1 tests and write tier-2/tier-3 files for three-phase CI |
| `testOrderRunTier` | Run tier 2 or tier 3 from a previous `testOrderTieredSelect` |
| `testOrderSelect` | Run the prioritized subset and write remaining tests to disk |
| `testOrderRunRemaining` | Run deferred tests written by `testOrderSelect` |
| `testOrderDump` | Dump the binary dependency index as human-readable text |
| `testOrderAggregate` | Re-aggregate `.deps` files into `test-dependencies.lz4` |
| `testOrderClean` | Remove all test-order generated files (index, state, hashes, deps) |
| `testOrderDiagnose` | Run diagnostic checks on the test-order setup |
| `testOrderOptimize` | Tune scoring weights based on failure history |
| `testOrderExportJson` | Export test-order data as JSON for scripting |
| `testOrderCoverage` | Analyze dependency coverage and identify gaps |
| `testOrderMetrics` | Export test-order metrics as JSON for CI/CD dashboards |
| `testOrderDownload` | Download dependency index from CI artifacts |
| `testOrderSnapshot` | Create hash snapshot for since-last-run change detection |
| `testOrderHelp` | List all available test-order tasks and properties |

All tasks are in the `test-order` group:

```bash
./gradlew tasks --group test-order
```

Serve options:

```bash
./gradlew testOrderServe -Dtestorder.dashboard.port=8080
./gradlew testOrderServe -Dtestorder.dashboard.regenerate=true
./gradlew testOrderServe -Dtestorder.dashboard.serveSeconds=30
```

### Tiered CI workflow (three-phase)

Split tests into three tiers for progressive fail-fast CI:

```bash
# Step 1: Run change-affected tests (tier 1)
./gradlew testOrderTieredSelect

# Step 2: Run top-scored remaining (tier 2) — only if step 1 passed
./gradlew testOrderRunTier -Dtestorder.tiered.currentTier=2

# Step 3: Run the rest (tier 3) — only if step 2 passed
./gradlew testOrderRunTier -Dtestorder.tiered.currentTier=3
```

Tiered properties:

| Property | Default | Description |
|---|---|---|
| `testorder.tiered.tier2Fraction` | `0.5` | Fraction of remaining duration budget for tier 2 |
| `testorder.tiered.weightByDuration` | `true` | Select tier 2 by duration budget (vs. count) |
| `testorder.tiered.currentTier` | — | Required for `testOrderRunTier`: `2` or `3` |

DSL equivalents:

```groovy
testOrder {
    tieredTier2Fraction = 0.5
    tieredWeightByDuration = true
}
```

Tier files are written to `build/test-order-tier1.txt`, `build/test-order-tier2.txt`, `build/test-order-tier3.txt`.

## How it works

### Learn mode

1. A Java agent (`-javaagent:test-order-agent.jar`) instruments application classes
   at load time, recording which ones each test class exercises.
2. The agent writes a binary dependency index (`.test-order/test-dependencies.lz4`) directly.
3. A `TelemetryListener` (JUnit `TestExecutionListener`, auto-discovered via ServiceLoader)
   records test durations and failures into `.test-order/state.lz4`.

### Order mode (auto)

1. The plugin detects changed source files (via `git diff` or hash comparison).
2. It injects `PriorityClassOrderer` as the JUnit `ClassOrderer`, which reads the
   dependency index and scores each test class based on overlap with changed code.
3. Tests with the highest scores run first.

### Auto mode

When `mode = "auto"` (default):
- If `test-dependencies.lz4` **does not exist** → learn mode.
- If `test-dependencies.lz4` **exists** → order mode.

## Scoring system

Each test class receives a score. Tests are sorted by descending score (highest first).

| Component | Default | Description |
|---|---|---|
| New test bonus | 15 | Bonus for test classes not in the dependency index |
| Changed test bonus | 9 | Bonus for test classes whose source was modified |
| Failure bonus | 1–5 | Capped bonus based on recent failure history (exponential decay) |
| Speed bonus | 1 | Continuous log₂ bonus for fast tests (scaled by `duration/median` ratio) |
| Speed penalty | 1 | Continuous log₂ penalty for slow tests (scaled by `duration/median` ratio) |
| Dependency overlap | 0–5 | `overlap / √totalDeps × weight` — proportional to changed-code coverage |
| Change complexity | 0–2 | Weighted by information density of changed files (Deflate size) |

Ties are broken by Jaccard diversity (maximising coverage breadth),
then by shorter duration, then alphabetically.

## Files produced

| File | Commit? | Description |
|---|---|---|
| `.test-order/test-dependencies.lz4` | **Yes** | Binary dependency index (compact, ~KB) |
| `.test-order/state.lz4` | Optional | Test durations, failure history, run records |
| `.test-order/hashes.lz4` | No | Source hash snapshot for since-last-run change detection |
| `build/test-order-deps/` | No | Intermediate `.deps` files (cleaned by `testOrderClean`) |

## Workflow recommendations

### Local development

```bash
# One-time: build the index
./gradlew test -Dtestorder.mode=learn

# Daily: just run tests (auto mode detects changes)
./gradlew test
```

### CI pipeline

```bash
# On main branch: refresh the index
./gradlew test -Dtestorder.mode=learn
# Commit test-dependencies.lz4

# On feature branches: fast feedback
./gradlew test
```

### Keeping the index fresh

Re-run learn mode periodically (e.g. nightly on CI) to capture new dependencies.
The index is compact (typically a few KB) and safe to commit.

## Compatibility

- **Java**: 17+
- **Gradle**: 7.6+ (tested with 8.x and 9.x)
- **JUnit**: Jupiter 5.x and 6.x
- **Build systems**: Works alongside other Gradle plugins (Spring Boot, Android, etc.)
- **Other agents**: Generally compatible with JaCoCo, MockitoAgent, etc.
  Agent ordering issues are rare but possible — test with your setup.
