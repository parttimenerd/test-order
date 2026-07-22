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
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'java'
    id 'me.bechberger.test-order' version '0.1.0'
}

repositories {
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
        mavenCentral()
    }
    dependencies {
        classpath 'me.bechberger:test-order-gradle-plugin:0.1.0'
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

### Troubleshooting: `mavenLocal()` and JUnit Platform conflicts

> The plugin automatically scopes its `mavenLocal()` repository to only resolve `me.bechberger` artifacts,
> so it will **not** pull in stale JUnit Platform JARs from `~/.m2`. Both the plugin DSL and init-script
> approaches work safely with JUnit 5 and JUnit 6 projects.
>
> If you still encounter **"Failed to load JUnit Platform"** errors, check that you haven't added an
> unscoped `mavenLocal()` elsewhere in your `build.gradle` or `settings.gradle`.
> Running `mvn dependency:purge-local-repository -DmanualInclude=org.junit` can clear stale JUnit artifacts.
>
> **Note:** If you scope `mavenLocal()` in `pluginManagement` for safety, include **both** groups:
> ```groovy
> pluginManagement {
>     repositories {
>         mavenLocal {
>             content {
>                 includeGroup("me.bechberger")
>                 includeGroup("me.bechberger.test-order") // plugin marker artifact
>             }
>         }
>         gradlePluginPortal()
>         mavenCentral()
>     }
> }
> ```

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
    // ---- Mode ----

    // Mode: "auto" (default) | "learn" | "order" | "optimize" | "skip"
    mode = "auto"

    // ---- Instrumentation ----

    // Agent instrumentation depth:
    //   MEMBER  — most detailed: per-method deps + field accesses (default)
    //   METHOD  — like MEMBER but without instance-field tracking
    //   CLASS   — method entries + field accesses only (lightest)
    //   FULL    — maximum granularity
    instrumentationMode = "MEMBER"

    // Instrumentation strategy:
    //   "offline" (default) — instrument bytecode at build time; no per-fork agent overhead
    //   "online"            — instrument at class-load time via agent; needed for dynamic class loading
    instrumentation = "offline"

    // LZ4 compression for index writes: "fast" (default) or "hc" (high compression)
    compression = "fast"

    // Comma-separated package prefixes to instrument.
    // Auto-detected from src/main/java if left empty.
    includePackages = ""

    // Fall back to project groupId as package filter when src/main/java scan finds nothing (default: true)
    filterByGroupId = true

    // Path for verbose agent logging (empty = disabled)
    verboseFile = ""

    // ---- Change Detection ----

    // Change detection: "uncommitted" (default) | "auto" | "since-last-run" | "since-last-commit" | "explicit"
    changeMode = "uncommitted"

    // For changeMode = "explicit": comma-separated FQCNs of changed production classes
    changedClasses = ""

    // Comma-separated FQCNs of changed test classes (CI integrations that detect changes externally)
    changedTestClasses = ""

    // Expand changed-class set via static call-graph analysis (default: true, up to 2 hops)
    staticAnalysisEnabled = true
    staticAnalysisDepth   = 2    // 0–4 hops

    // ---- Ordering ----

    // Enable method-level ordering within test classes (default: false)
    methodOrderingEnabled = false

    // Optional path to a JSON weights override file
    weightsFile = ""

    // Number of top-scored tests to always select (-1 = all change-affected, default: -1)
    selectTopN    = -1
    // Number of diverse fast tests to additionally select in auto/order mode (default: 10)
    selectRandomM = 10
    // Number of diverse fast tests to additionally select in testOrderAffected (default: 0)
    // Matches Maven AffectedMojo default; set higher to also sample random fast tests
    affectedSelectRandomM = 0
    // Random seed for deterministic selection (null = random)
    // selectSeed = 42L

    // ---- Paths ----

    indexFile = file(".test-order/test-dependencies.lz4")
    stateFile = file(".test-order/state.lz4")
    depsDir   = layout.buildDirectory.dir("test-order-deps")

    // ---- Auto-mode behavior ----

    // Force a full re-learn after N consecutive order-mode runs (0 = disabled, default: 10)
    autoLearnRunThreshold  = 10
    // Force learn when changed-class count reaches N (0 = disabled)
    autoLearnDiffThreshold = 0
    // Run weight optimisation every N order-mode runs (0 = disabled, default: 10)
    autoOptimizeEvery      = 10
    // Auto-compact index every N order-mode runs (0 = disabled, default: 50)
    autoCompactEvery       = 50

    // In auto mode, also run remaining tests after the affected subset (default: false for testOrderAffected)
    autoRunRemaining = false

    // Always attach the learn-mode agent even in order runs (incremental refinement, default: false)
    alwaysLearn = false
    // Only instrument classes in the static call graph of changed classes (default: false)
    selectiveLearn = false

    // Group Spring context tests together for scoring (default: false)
    springContextGrouping = false

    // ---- TDD enforcement ----

    // New tests that pass without failing first are artificially failed (default: false)
    tdd = false

    // ---- Non-standard layouts ----

    // Explicit main source root (replaces auto-detection from source sets)
    // sourceRoot = "src/main/java"
    // Explicit test source root
    // testSourceRoot = "src/test/java"

    // ---- Scoring Weights ----
    // When not set, PriorityClassOrderer uses optimizer-tuned weights from state.lz4
    // or its own built-in defaults. Setting these here overrides the optimizer.

    scoreNewTest              = 15
    scoreChangedTest          = 9
    scoreMaxFailure           = 5
    scoreSpeed                = 1
    scoreSpeedPenalty         = 1
    scoreDepOverlap           = 5
    scoreChangeComplexity     = 2
    scoreStaticFieldBonus     = 0
    scoreCoverageBonus        = 0   // bonus for well-covered classes
    scoreKillRateBonus        = 0   // bonus based on PIT kill rate
    scorePackageProximityBonus = 0  // bonus for tests in the same package as changed code

    // ---- Dump ----

    // Output file for testOrderDump (empty = stdout)
    dumpOutputFile = ""

    // ---- Coverage ----

    coverageThreshold = 2   // minimum exercising tests for "well-tested"
    coverageOutputDir = layout.buildDirectory.dir("coverage-reports")
}
```

### Per-task mode override

Override the mode for a specific test task without affecting others:

```bash
./gradlew integrationTest -Dtestorder.mode.integrationTest=order
```

### Property overrides

Most settings can be overridden on the command line via `-D` (system property) or `-P` (Gradle project property).
Score weights must be set in the `testOrder { }` DSL block (they don't map to system properties).

```bash
# System property
./gradlew test -Dtestorder.mode=learn

# Gradle property
./gradlew test -Ptestorder.mode=learn
```

## Tasks

All tasks are in the `test-order` group:

```bash
./gradlew tasks --group test-order
```

### Core test-execution tasks

| Task | Maven equivalent | Description |
|---|---|---|
| `testOrderLearn` | `test-order:learn` | Run tests in learn mode (always instruments, regardless of current mode) |
| `testOrderAffected` | `test-order:affected` | Run the prioritized subset; write remaining tests to disk for `testOrderRunRemaining` |
| `testOrderRunRemaining` | `test-order:run-remaining` | Run deferred tests written by `testOrderAffected` |
| `testOrderTieredSelect` | `test-order:run-tiered` (tier 1) | Run tier-1 (change-affected) tests and write tier-2/tier-3 lists |
| `testOrderRunTier` | `test-order:run-tiered` (tier 2/3) | Run tier 2 or tier 3 from a previous `testOrderTieredSelect` |
| `testOrderRunTiered` | `test-order:run-tiered` | Run all three tiers in a single test execution (Maven parity) |

### Index maintenance

| Task | Maven equivalent | Description |
|---|---|---|
| `testOrderInstrument` | `test-order:instrument` | Instrument compiled classes at build time (offline mode) |
| `testOrderAggregate` | `test-order:aggregate` | Re-aggregate `.deps` files into `test-dependencies.lz4` |
| `testOrderCompact` | `test-order:compact` | Rebuild index from `.deps` files, removing stale entries |
| `testOrderClean` | `test-order:clean` | Remove all test-order generated files (index, state, hashes, deps) |
| `testOrderSnapshot` | `test-order:snapshot` | Create hash snapshot for since-last-run change detection |
| `testOrderPrepare` | `test-order:prepare` | Validate configuration, restore offline classes, print what mode would be used |
| `testOrderDownload` | `test-order:download` | Download dependency index from CI artifacts |

### Reporting and inspection

| Task | Maven equivalent | Description |
|---|---|---|
| `testOrderShow` | `test-order:show` | Unified view: class order, method order, ML health (auto-detects) |
| `testOrderShowAll` | — | Like `testOrderShow` but includes all test classes, not just change-affected ones |
| `testOrderShowOrder` | — | _(deprecated → use `testOrderShow`)_ Display predicted test execution order |
| `testOrderShowMethodOrder` | — | Display predicted method execution order within test classes |
| `testOrderShowStaticAnalysis` | — | Show which members changed and which callers were pulled in by call-graph expansion |
| `testOrderReactorOrder` | `test-order:reactor-order` | Show recommended module execution order for multi-project builds |
| `testOrderExplain` | `test-order:explain` | Explain why a test (or method) was scored: `-Ptest=com.example.MyTest` or `-Ptest=com.example.MyTest#myMethod` |
| `testOrderExplainOrder` | — | Display detailed per-test score explanations for all classes |
| `testOrderExplainMethodOrder` | — | Display detailed per-method score explanations |
| `testOrderDump` | `test-order:dump` | Dump the binary dependency index as human-readable text |
| `testOrderExportJson` | `test-order:export-json` | Export test-order data as JSON for scripting |
| `testOrderDashboard` | `test-order:dashboard` | Generate an interactive HTML dashboard from current index/state |
| `testOrderServe` | — | Serve dashboard over local HTTP |
| `testOrderMetrics` | `test-order:metrics` | Export test-order metrics as JSON for CI/CD dashboards |
| `testOrderCoverage` | `test-order:coverage` | Analyze dependency coverage and identify gaps |

### Analysis and tuning

| Task | Maven equivalent | Description |
|---|---|---|
| `testOrderOptimize` | `test-order:optimize` | Tune scoring weights based on failure history |
| `testOrderDetectDependencies` | `test-order:detect-dependencies` | Detect order-dependent (flaky) tests via reordering strategies |
| `testOrderAnalyzeMutations` | `test-order:analyze-mutations` | Run PIT mutation testing and record kill-rate data for scoring |
| `testOrderDiagnose` | `test-order:diagnose` | Run diagnostic checks on the test-order setup |
| `testOrderHelp` | `test-order:help` | List all available test-order tasks and properties |

### Serve options

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

## TDD Enforcement

Enforce test-driven development discipline: new test classes and methods that
pass without having failed first are artificially failed.

```groovy
testOrder {
    tdd = true
}
```

Or via the command line:

```bash
./gradlew test -Dtestorder.tdd=true
```

On the first run (no state file), enforcement is skipped.
After the first learn run, any new test that passes without a prior failure is flagged
with a descriptive `TDD VIOLATION` error.

## Scoring system

Each test class receives a score. Tests are sorted by descending score (highest first).

| Component | Default | Description |
|---|---|---|
| New test bonus | 15 | Bonus for test classes not in the dependency index |
| Changed test bonus | 9 | Bonus for test classes whose source was modified |
| Failure bonus | 5 | Capped bonus based on recent failure history (exponential decay) |
| Speed bonus | 1 | Continuous log₂ bonus for fast tests (scaled by `duration/median` ratio) |
| Speed penalty | 1 | Continuous log₂ penalty for slow tests (scaled by `duration/median` ratio) |
| Dependency overlap | 0–5 | `overlap / √totalDeps × weight` — proportional to changed-code coverage |
| Change complexity | 0–2 | Weighted by information density of changed files (Deflate size) |
| Coverage bonus | 0 | Extra weight for well-tested classes (configurable via `scoreCoverageBonus`) |
| Kill-rate bonus | 0 | Extra weight from PIT mutation kill rate (after `testOrderAnalyzeMutations`) |
| Package proximity | 0 | Bonus for tests in the same package as changed production classes |

Ties are broken by Jaccard diversity (maximising coverage breadth),
then by shorter duration, then alphabetically.

When no scoring weights are set in the DSL, the plugin uses optimizer-tuned values from
`state.lz4` (after running `testOrderOptimize`) or its own built-in defaults.
**Setting any weight in the DSL overrides the optimizer output for that weight.**

## Files produced

| File | Commit? | Description |
|---|---|---|
| `.test-order/test-dependencies.lz4` | **Yes** | Binary dependency index (compact, ~KB) |
| `.test-order/state.lz4` | Optional | Test durations, failure history, run records |
| `.test-order/ml/history.lz4` | Optional | ML run history (when `ml.enabled=true`) |
| `.test-order/hashes.lz4` | No | Source hash snapshot for since-last-run change detection |
| `build/test-order-deps/` | No | Intermediate `.deps` files (cleaned by `testOrderClean`) |

## ML Failure Predictions

The plugin includes an ML layer that learns from test history to predict failures and classify test health.

### Enabling ML

```groovy
testOrder {
    ml = true
}
```

Or via system property:

```bash
./gradlew test -Dtestorder.ml.enabled=true
```

When enabled, per-test outcomes are recorded into `.test-order/ml/history.lz4` after each run.

### ML Properties

| Property | Default | Description |
|---|---|---|
| `testorder.ml.enabled` | `false` | Enable ML history collection and predictions |

### Viewing ML Results

```bash
# Unified show task (auto-detects ML history)
./gradlew testOrderShow

# Explicitly enable ML section
./gradlew testOrderShow -Dtestorder.show.ml=true

# JSON output
./gradlew testOrderShow -Dtestorder.show.format=json
```

### ML in Dashboard

```bash
./gradlew testOrderDashboard
```

The dashboard automatically includes an **ML Health** tab and a **P(fail)** column when ML history exists. No extra flags needed.

### What ML Provides

After 5+ recorded runs:
- **P(fail) predictions** — Logistic regression on 26 features predicts failure probability per test
- **Health classification** — HEALTHY, DEGRADING (trend worsening), FLAKY (inconsistent), FAILING (broken)
- **Co-failure tracking** — Tests that tend to fail together are identified

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

## Multi-module builds

In multi-project builds the plugin shares a single dependency index across all subprojects
(stored at the root project level in `<root>/.test-order/`), mirroring how the Maven plugin
uses a shared reactor context. Hash snapshots are stored per-subproject to avoid one module
overwriting another's baseline:

```
<root>/.test-order/
├── test-dependencies.lz4       # shared index (all modules)
├── state.lz4                   # shared state
└── hashes/
    ├── module-a-hashes.lz4
    ├── module-a-test-hashes.lz4
    ├── module-b-hashes.lz4
    └── ...
```

To see recommended module execution order based on dependency overlap:

```bash
./gradlew testOrderReactorOrder
# Machine-readable -pl argument:
./gradlew testOrderReactorOrder -Dtestorder.reactor.suggest=true
```

To re-aggregate all subproject `.deps` files into one shared index:

```bash
./gradlew testOrderAggregateAll
```

## Offline vs online instrumentation

| Mode | Default | When to use |
|---|---|---|
| `offline` | Yes | Production use. Bytecode rewritten once at build time; no per-fork JVM overhead |
| `online` | No | Classes loaded dynamically (e.g. OSGi, custom class loaders). Agent runs in each test fork |

Switch to online instrumentation:

```groovy
testOrder {
    instrumentation = "online"
}
```

Or per-run:

```bash
./gradlew test -Dtestorder.instrumentation=online -Dtestorder.mode=learn
```

When using offline instrumentation, `testOrderInstrument` pre-processes compiled classes before
the test task runs. The original classes are restored by `testOrderOfflineRestore` (a `doLast`
action on the test task) so subsequent non-test-order builds see the original bytecode.

## Static call-graph analysis

In order mode the plugin expands the changed-class set by traversing transitive callers in the
compiled bytecode, pulling in additional tests that call (directly or indirectly) the changed code:

```groovy
testOrder {
    staticAnalysisEnabled = true   // default
    staticAnalysisDepth   = 2      // 0–4 hops
}
```

To inspect what was detected as changed and what callers were pulled in:

```bash
./gradlew testOrderShowStaticAnalysis
./gradlew testOrderShowStaticAnalysis -Dtestorder.showStaticAnalysis.verbose=true
```

## Explaining scores

```bash
# Explain a test class
./gradlew testOrderExplain -Ptest=com.example.MyServiceTest

# Explain a specific method
./gradlew testOrderExplain -Ptest=com.example.MyServiceTest#shouldReturnEmpty

# Show all class scores with full breakdown
./gradlew testOrderExplainOrder

# Show all method scores with full breakdown
./gradlew testOrderExplainMethodOrder
```

## Selective learn

When learning only a subset of classes (e.g. on CI with many unchanged modules), you can
restrict instrumentation to classes reachable from your changed code:

```groovy
testOrder {
    selectiveLearn = true   // only instrument call-graph of changed classes
}
```

Combine with `alwaysLearn = true` to incrementally refine the index on every order run
without a separate full learn step.

## Compatibility

- **Java**: 17+
- **Gradle**: 7.6+ (tested with 8.x and 9.x)
- **JUnit**: Jupiter 5.x and 6.x
- **Build systems**: Works alongside other Gradle plugins (Spring Boot, Android, etc.)
- **Other agents**: Generally compatible with JaCoCo, MockitoAgent, etc.
  Agent ordering issues are rare but possible — test with your setup.
