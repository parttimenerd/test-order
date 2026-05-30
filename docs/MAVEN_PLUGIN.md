# Maven Plugin Reference

Complete reference for the test-order Maven plugin goals, parameters, and CI integration.

## Automatic Dependency Change Detection

The plugin fingerprints the **resolved test classpath** (JAR names, sizes, and timestamps) on every run. When it detects a change — such as a SNAPSHOT rebuild, a version bump, or a new transitive dependency — it automatically switches to learn mode to rebuild the index:

```
[test-order] Dependency change detected (resolved classpath differs)
  — switching to learn mode to refresh index.
```

Detected changes include:
- **SNAPSHOT updates** — rebuilt SNAPSHOTs have new timestamps/sizes
- **Version bumps** — e.g. upgrading `spring-boot` from 3.1 to 3.2
- **Added/removed transitive dependencies** — new JARs on the classpath
- **Lock-file changes** — Gradle lock files or version catalogs

The fingerprint is stored in the state file (`.test-order/state.lz4`) and only compared against the previous run's classpath.

## Always-on Instrumentation

Instead of periodic learn runs, keep the agent attached on every test run:

```xml
<configuration>
  <mode>learn</mode>
</configuration>
```

**Trade-offs:**
- **5–30% overhead** from the Java agent recording test dependencies on every run (varies by instrumentation mode; MEMBER mode, the default, is ~10–30%)
- **Potential behaviour differences** with timing-sensitive tests or other bytecode transformers
- **Agent conflicts** possible with JaCoCo, MockitoAgent — test your specific setup

For most projects, periodic learn mode on CI is simpler.

## Instrumentation Filtering

The Java agent supports configurable filtering strategies:

| Strategy | Behaviour |
|---|---|
| `WHITELIST` | Instrument only explicitly included packages |
| `BLACKLIST` | Instrument everything except excluded packages |
| `SMART` | Use includes when provided, otherwise broad with exclusions |
| `WHITELIST_SMART` | Strict include-only with smart heuristics |

Key options: `includePackages`, `excludePackages`, `filterStrategy`, `skipTestClasses`, `useHeuristics`, `autoDetectPackages`, `projectRoot`.

Auto-detection (enabled by default) analyses `pom.xml` / `build.gradle*` plus `src/main/*` and `src/test/*` package layout.

## Goals

| Goal | Description |
|---|---|
| `test-order:prepare` | Validates setup and writes plugin/runtime configuration |
| `test-order:learn` | Attach agent for learn mode (pair with `test` phase) |
| `test-order:auto` | Main workflow: select high-value subset and run it |
| `test-order:select` | Write selected tests to file; configure Surefire |
| `test-order:run-remaining` | Execute deferred tests from prior selection |
| `test-order:tiered-select` | Split tests into tier 1/2/3 files and run tier 1 |
| `test-order:run-tier` | Execute tier 2 or tier 3 from prior tiered selection |
| `test-order:show` | Unified view: class order, method order, ML health (auto-detects) |
| `test-order:explain` | Print detailed per-test score breakdown for the current change set |
| `test-order:show-static-analysis` | Show static call-graph expansion details (verbose) |
| `test-order:reactor-order` | Compute optimal module execution order for multi-module builds |
| `test-order:dashboard` | Generate interactive HTML dashboard |
| `test-order:serve` | Serve dashboard via local HTTP server |
| `test-order:optimize` | Re-optimise scoring weights from run history |
| `test-order:snapshot` | Save source/test file hash snapshots |
| `test-order:aggregate` | Merge `.deps` files into dependency index |
| `test-order:dump` | Print dependency index contents |
| `test-order:export-json` | Export dependency index as JSON |
| `test-order:diagnose` | Run diagnostic health checks |
| `test-order:compact` | Rebuild dependency index from `.deps` files (removes stale entries) |
| `test-order:clean` | Remove all test-order state, indexes, and hashes |
| `test-order:download` | Download dependency index from CI artifact store |
| `test-order:coverage` | Generate least-tested / coverage reports |
| `test-order:metrics` | Export test-order metrics as JSON for CI/CD reporting |
| `test-order:analyze-mutations` | Run PIT mutation testing and record kill-rate data for scoring |
| `test-order:detect-dependencies` | Detect order-dependent tests via reordering strategies |
| `test-order:help` | Display all goals and common properties |

> `test-order:learn` only prepares learn mode — always pair it with the `test` phase to actually execute tests. Similarly, `test-order:select` configures Surefire but needs `test` to run the selected subset.

## Show Goal

The unified `show` goal displays test ordering, method-level priorities, and ML health analysis in a single command:

```bash
# Default: show class order + method order (ML auto-detected)
mvn test-order:show

# All sections explicitly
mvn test-order:show -Dtestorder.show.all=true

# JSON for CI tooling
mvn test-order:show -Dtestorder.show.format=json
```

### Show Properties

| Property | Default | Description |
|---|---|---|
| `testorder.show.classes` | `true` | Show class-level priority order |
| `testorder.show.methods` | `auto` | Show method-level priority order (`auto` = show if method telemetry exists) |
| `testorder.show.ml` | `auto` | ML health section: `auto` (show if history exists), `true`, `false` |
| `testorder.showOrder.explain` | `false` | Show per-test score breakdown |
| `testorder.showOrder.fullNames` | `false` | Print fully-qualified class names |
| `testorder.show.format` | `text` | Output format: `text` or `json` |
| `testorder.show.filter` | — | Glob filter for test class names — matches the full FQCN; use `*` as wildcard (e.g. `*Service*,*Repository`) |
| `testorder.select.topN` | `-1` | Show only top N tests (`-1` = all) |
| `testorder.select.randomM` | `10` | Include M random diverse tests |
| `testorder.select.seed` | — | Random seed for `randomM` |
| `testorder.show.all` | `false` | Enable all sections (classes + methods + ML) |

> **CamelCase aliases:** `testorder.showOrder.format` is accepted as an alias for `testorder.show.format`,
> and `testorder.showOrder.topN` is accepted as an alias for `testorder.select.topN`.
> Both aliases log an info-level message pointing to the canonical name.

## Explain Goal

The `explain` goal prints a detailed per-test score breakdown so you can understand why each test is ranked where it is:

```bash
# Explain top 10 tests for the current change set
mvn test-order:explain -Dtestorder.changed.classes=com.example.Foo

# Explain a specific test only
mvn test-order:explain \
    -Dtestorder.changed.classes=com.example.Foo \
    -Dtestorder.explain.test=com.example.FooTest

# Explain top 5
mvn test-order:explain -Dtestorder.explain.topN=5
```

| Property | Default | Description |
|---|---|---|
| `testorder.explain.test` | — | Fully-qualified name of the test to explain; if omitted, top-N tests are explained |
| `testorder.explain.topN` | `10` | Number of top-ranked tests to explain when `testorder.explain.test` is not set |

## Plugin Prefix Resolution

If Maven reports `No plugin found for prefix 'test-order'`, use one of these solutions:

1. **Add plugin group to `~/.m2/settings.xml`** (recommended):
   ```xml
   <settings>
     <pluginGroups>
       <pluginGroup>me.bechberger</pluginGroup>
     </pluginGroups>
   </settings>
   ```

2. **Use fully-qualified coordinates**:
   ```bash
   mvn me.bechberger:test-order-maven-plugin:0.0.1-SNAPSHOT:detect-dependencies
   ```

3. **Declare the plugin in your POM** (if not already):
   ```xml
   <build>
     <plugins>
       <plugin>
         <groupId>me.bechberger</groupId>
         <artifactId>test-order-maven-plugin</artifactId>
         <version>0.0.1-SNAPSHOT</version>
         <extensions>true</extensions>  <!-- required: registers the lifecycle participant that writes the index -->
       </plugin>
     </plugins>
   </build>
   ```

## Detecting Order-Dependent Tests

The `detect-dependencies` goal discovers tests that pass or fail depending on execution order:

```bash
mvn test-order:detect-dependencies
```

### Detection Properties

| Property | Default | Description |
|---|---|---|
| `testorder.detect.algorithm` | `combined` | Detection strategy (see below) |
| `testorder.detect.timeBudget` | `300` | Time budget in seconds (0 = unlimited) |
| `testorder.detect.stopOnFirst` | `false` | Stop after first finding |
| `testorder.detect.seed` | `42` | Random seed for reproducibility |
| `testorder.detect.failOnDetection` | `false` | Fail the build if ODs are found |

### Algorithm Recommendations

| Algorithm | Runs | Best For |
|---|---|---|
| `combined` | Adaptive | **Default.** Tries reverse, random, and history strategies adaptively. Best general-purpose choice. |
| `reverse` | 1 | Quick smoke-checks with minimal cost. |
| `random` | Many | Generous time budgets; explores diverse orderings. |
| `history` | Varies | Leveraging prior run data to target suspicious tests. |
| `pfast` | Varies | Large suites; probabilistic approach (Pradet-style). |
| `iterative` | O(n²) | Thorough pairwise iteration; slow but exhaustive. |
| `bounded` | Fixed | Random with a bounded number of runs. |
| `tuscan` | Covering | Systematic coverage via covering arrays. |

### Incremental Detection

If a previous JSON report exists at `.test-order/detection/od-detection-report.json`, the goal loads it and
skips re-testing known victims. This makes repeated runs cheaper.

### Multi-Module Projects

The goal automatically iterates reactor modules that have `src/test/java`. Each module
runs detection independently. The build fails if any module has findings (when
`failOnDetection=true`).

## Reactor Module Order

The `reactor-order` goal analyzes which modules contain the highest-priority tests and
recommends a `-pl` argument for running the most urgent modules first.

```bash
# Show recommended module order
mvn test-order:reactor-order

# Get a machine-readable -pl argument
mvn test-order:reactor-order -Dtestorder.reactor.suggest=true
```

| Property | Default | Description |
|---|---|---|
| `testorder.reactor.suggest` | `false` | Output only the `-pl` argument (machine-parseable for scripts) |
| `testorder.reactor.topN` | `5` | Number of top tests to show per module in detailed output |

## TDD Enforcement

Enforce test-driven development discipline: new test classes and methods that
pass without having failed first are artificially failed.

```bash
mvn test -Dtestorder.tdd=true
```

Or set it in the plugin configuration:

```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <tdd>true</tdd>
    </configuration>
</plugin>
```

On the first run (no state file), enforcement is skipped so existing projects
can adopt it without breaking. After the first learn run builds state,
any new test that passes without a prior failure is flagged:

```
═══════════════════════════════════════════════════════════════
  TDD VIOLATION: New test CLASS passed without failing first
  Test: com.example.MyNewTest#shouldWork

  In TDD, write the test first, see it FAIL,
  then implement the code to make it pass.

  Disable with: -Dtestorder.tdd=false
═══════════════════════════════════════════════════════════════
```

## Learn Mode

Collect dependency data:

```bash
mvn test -Dtestorder.mode=learn
```

By default this uses `MEMBER` instrumentation (the most accurate mode).
To use a lighter instrumentation mode:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=METHOD
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=CLASS
```

This run writes/updates `.test-order/test-dependencies.lz4` directly.

Use `mvn test-order:aggregate` only when you intentionally aggregate fallback `.deps` files.

## ML Failure Predictions

The ML subsystem learns from test history to predict which tests are most likely to fail and to classify test health over time.

### Enabling ML

```bash
# Pass as system property
mvn test -Dtestorder.ml.enabled=true

# Or add to plugin configuration
```
```xml
<configuration>
  <ml>true</ml>
</configuration>
```

When enabled, the `TelemetryListener` records per-test outcomes (pass/fail, duration, timestamp) into `.test-order/ml/history.lz4` after each test run.

### ML Properties

| Property | Default | Description |
|---|---|---|
| `testorder.ml.enabled` | `false` | Enable ML history collection and predictions |
| `testorder.ml.predictions.file` | _(auto)_ | Intermediate predictions file consumed by the test JVM |

### How It Works

1. **History collection** — Each test run appends outcomes to `history.lz4` (LZ4-compressed binary format). The ring buffer discards the oldest runs beyond `maxRuns`.

2. **Feature extraction** — After 5+ recorded runs, `MLFeatureExtractor` computes 26 features per test class:
   - EWMA failure rate and trend (α=0.3)
   - Recent failure streak and last-failure recency
   - Duration variance and mean
   - Co-failure proximity (Jaccard similarity via `CoFailureTracker`)
   - Change coupling signals (5 features)
   - Package/dependency statistics (9 features)
   - Time-series properties (4 features: autocorrelation, volatility, slope, seasonal)

3. **Prediction** — `TestFailurePredictor` trains a Tribuo logistic regression model in-process. Failures are weighted 5× to emphasize rare but important events. Each test class gets a P(fail) score ∈ [0, 1].

4. **Health classification** — `TestHealthAnalyzer` assigns one of four statuses:
   - **HEALTHY** — Passes consistently (low failure rate, stable)
   - **DEGRADING** — Failure trend ≥ 0.02 (getting worse)
   - **FLAKY** — Volatility ≥ 0.15 or autocorrelation ≤ -0.3 (inconsistent)
   - **FAILING** — Failure rate ≥ 0.8 (broken)

### Viewing ML Results

```bash
# Unified show command (auto-detects ML history)
mvn test-order:show

# Explicitly request ML section
mvn test-order:show -Dtestorder.show.ml=true

# JSON output for CI tooling
mvn test-order:show -Dtestorder.show.format=json -Dtestorder.show.ml=true
```

### ML in Dashboard

The `dashboard` goal automatically detects ML history and includes:
- **ML Health tab** — breakdown of test health statuses with EWMA charts
- **P(fail) column** — in the main tests table, showing predicted failure probability

No extra flags needed; if `.test-order/ml/history.lz4` exists with sufficient data, the dashboard renders ML insights.

### Dashboard Properties

| Property | Default | Description |
|---|---|---|
| `testorder.dashboard.output` | `target/test-order-dashboard/index.html` | Output path for the generated HTML file |
| `testorder.dashboard.open` | `false` | Open the dashboard in the default browser after generation |
| `testorder.dashboard.port` | `0` | TCP port for `test-order:serve` (`0` = pick a free port automatically) |
| `testorder.serve.port` | — | Alias for `testorder.dashboard.port` (accepted for convenience) |
| `testorder.dashboard.regenerate` | `auto` | When to regenerate before serving: `auto` (only if missing), `true` (always), `false` (never — fail if missing) |
| `testorder.dashboard.serveSeconds` | `0` | Bounded server lifetime for `test-order:serve`. `0` = run until interrupted (Ctrl+C). Set to a positive number for CI use |

### CI Integration

```yaml
# Collect ML history on every run
test:
  steps:
    - run: mvn test -Dtestorder.ml.enabled=true

# Periodically generate dashboard with ML insights
dashboard:
  steps:
    - run: mvn test-order:dashboard
    - uses: actions/upload-artifact@v4
      with:
        name: test-dashboard
        path: target/test-order-dashboard/
```

## Index Compaction

The dependency index (`.test-order/test-dependencies.lz4`) grows over time as learn
runs add entries. When test classes are renamed, deleted, or moved, their old entries
become stale but remain in the index. `compact` rebuilds the index from scratch using
only the current `.deps` files:

```bash
mvn test-order:compact
```

This is useful when:
- The index has grown large with stale entries from deleted/renamed tests
- The index file is corrupted (e.g., partial write, disk error)
- You want to verify the index matches the current `.deps` data

By default, the plugin runs compaction automatically every 50 order-mode runs. You can
tune this with `testorder.autoCompactEvery` (set to `0` to disable automatic compaction).

## Order Mode

With an existing `.test-order/test-dependencies.lz4`, tests are automatically reordered:

```bash
mvn test -Dtestorder.mode=order
```

## Configuration Precedence

When the same setting is provided in multiple places, priority is:

1. System properties (`-Dtestorder.*`)
2. Weights file passed via `-Dtestorder.weights.file=...`
3. Plugin `<configuration>` in `pom.xml`
4. Persisted state file values (`.test-order/state.lz4`) such as optimized weights and run history
5. Internal defaults

## Auto Mode (default)

If `testorder.mode` is `auto` (the default), the plugin checks for a `testorder.learn` system property.
In order mode it falls back through: explicit classes → hash-based → git-based change detection.

## Select Mode (Two-phase CI Workflow)

Split your test suite into two Maven invocations for fast feedback:

```bash
# Phase 1 — run the critical subset (fail-fast)
mvn test-order:select test

# Phase 2 — if phase 1 passed, run everything else
mvn test-order:run-remaining test
```

**Phase 1 (`select`)** picks tests in four priority tiers:

1. **`@AlwaysRun` tests** — unconditionally included, pinned first.
2. **New tests** — classes not yet in the dependency index.
3. **Affected tests** — tests whose dependency set overlaps with changed classes, ranked by score.
4. **Diverse fast tests** — `M` additional fast tests chosen greedily by Jaccard distance.

Selected test FQCNs are written to `target/test-order-selected.txt`.
All other classes go to `target/test-order-remaining.txt`.

### Selection Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `topN` | `testorder.select.topN` | `-1` | Number of top-scored affected tests to include (`-1` = all affected, positive = exact count, `0` = no top-scored tests; new and `@AlwaysRun` tests still included, a warning is emitted). |
| `randomM` | `testorder.select.randomM` | `10` | Number of random fast tests for coverage diversity |
| `seed` | `testorder.select.seed` | — | Random seed for reproducible selection |
| `remainingFile` | `testorder.select.remainingFile` | `target/test-order-remaining.txt` | File for deferred test classes |
| `selectedFile` | `testorder.select.selectedFile` | `target/test-order-selected.txt` | File for selected test classes |

## Auto Mode (Combined Goal)

A single goal that handles the full workflow automatically:

1. No dependency index → learns
2. `runsSinceLearn >= autoLearnRunThreshold` (default `10`) → re-learns
3. Otherwise → selects a fast subset and configures Surefire

```bash
mvn test-order:auto test
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `runRemaining` | `testorder.auto.runRemaining` | `true` | Automatically run remaining tests after the selected subset |
| `optimizeEvery` | `testorder.auto.optimizeEvery` | `10` | Optimise weights every N runs (0 = never) |
| `autoLearnRunThreshold` | `testorder.autoLearnRunThreshold` | `10` | Force a full learn pass every N runs (0 = disable) |

Then run the deferred tests only when the first command succeeds:

```bash
mvn test-order:auto test && mvn test-order:run-remaining test
```

## Recommended CI Setup

```yaml
# .github/workflows/ci.yml (example)
jobs:
  # Run learn mode daily on main branch to keep the dependency index fresh
  learn:
    if: github.event_name == 'schedule'
    steps:
      - run: mvn test -Dtestorder.mode=learn
      - run: git add .test-order/ && git commit -m "update test-order data" && git push

  # Every PR: two-phase workflow
  test-fast:
    steps:
      - run: mvn test-order:select test
  test-remaining:
    needs: test-fast
    steps:
      - run: mvn test-order:run-remaining test

  # Weekly: optimise scoring weights
  optimize:
    if: github.event_name == 'schedule'  # weekly cron
    steps:
      - run: mvn test-order:optimize
      - run: git add .test-order/state.lz4 && git commit -m "optimise test-order weights" && git push
```

## Plugin Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `skip` | `testorder.skip` | `false` | Skip the plugin entirely |
| `mode` | `testorder.mode` | `auto` | `auto`, `learn`, `order`, or `skip` |
| `indexFile` | `testorder.index.path` (alias: `testorder.index`) | `${project.basedir}/.test-order/test-dependencies.lz4` | Dependency index path |
| `depsDir` | `testorder.depsDir` | `${project.build.directory}/test-order-deps` | Directory for `.deps` files |
| `includePackages` | `testorder.includePackages` | — | Additional comma-separated package prefixes to instrument |
| `filterByGroupId` | `testorder.filterByGroupId` | `true` | Fall back to groupId when no source packages are detected |
| `instrumentationMode` | `testorder.instrumentation.mode` | `MEMBER` | `CLASS`, `METHOD`, or `MEMBER` |
| `changeMode` | `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `changedClasses` | `testorder.changed.classes` | — | Explicit changed class FQCNs |
| `hashFile` | `testorder.hashFile` | `${project.basedir}/.test-order/hashes.lz4` | LZ4-compressed hash store |
| `testHashFile` | `testorder.testHashFile` | `${project.basedir}/.test-order/test-hashes.lz4` | Hash store for test sources |
| `stateFile` | `testorder.state.path` (alias: `testorder.stateFile`) | `${project.basedir}/.test-order/state.lz4` | Unified state file |
| `weightsFile` | `testorder.weights.file` | — | Optional scoring weights file |
| `scoreNewTest` | `testorder.score.newTest` | `15` | Bonus for new test classes |
| `scoreChangedTest` | `testorder.score.changedTest` | `9` | Bonus for changed test sources |
| `scoreMaxFailure` | `testorder.score.maxFailure` | `5` | Cap on failure-based bonus |
| `scoreSpeed` | `testorder.score.speed` | `1` | Bonus for fast tests |
| `scoreSpeedPenalty` | `testorder.score.speedPenalty` | `1` | Penalty for slow tests |
| `scoreDepOverlap` | `testorder.score.depOverlap` | `5` | Max score from dependency overlap |
| `scoreChangeComplexity` | `testorder.score.changeComplexity` | `2` | Complexity-weighted overlap |
| `scoreStaticFieldBonus` | `testorder.score.staticFieldBonus` | `0` | Bonus for changed static field overlap |

## Skipping the Plugin

```bash
mvn test -Dtestorder.skip=true
```

Or set `<skip>true</skip>` in the plugin `<configuration>` block.

## Advanced Configuration

Beyond the standard plugin parameters, these system properties control advanced behaviour:

### Auto-learn

| Property | Default | Description |
|---|---|---|
| `testorder.autoLearnRunThreshold` | `10` | Force re-learn after N order-mode runs (`0` = disabled) |
| `testorder.autoLearnDiffThreshold` | `0` (disabled) | Automatically re-learn when changed file count ≥ threshold |

### Additional Scoring Properties

| Property | Default | Description |
|---|---|---|
| `testorder.score.coverageBonus` | `0` (disabled) | Set-cover algorithm bonus for coverage diversity |
| `testorder.score.springContextGrouping` | — | Bonus for Spring-annotated tests sharing context |
| `testorder.score.ema.varianceThreshold` | — | EMA variance threshold for duration stability — stored in state file only; setting via `-D` has no effect |

### Runtime Properties

| Property | Description |
|---|---|
| `testorder.debug` | Enable verbose debug output for ordering and change detection |
| `testorder.project.root` | Git project root for change detection |
| `testorder.source.root` | Custom source root (overrides auto-detected `src/main/java`) |
| `testorder.history.maxRuns` | Maximum run history entries (default: 50) |
| `testorder.structuralDiff.enabled` | Enable/disable structural change analysis (default: true) |
| `testorder.changed.classes.file` | Read changed classes from a file (one fully-qualified class name per line; blank lines ignored) |
| `testorder.changed.methods` | Explicit changed production methods in `className#methodName` format (comma-separated). Affects method-level scoring; use with `changeMode=explicit` to restrict scoring to specific changed methods (e.g. `com.example.Foo#doWork,com.example.Bar#process`) |

## Coverage Analysis

The `coverage` goal analyses the dependency index to identify least-tested production classes:

```bash
mvn test-order:coverage
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `threshold` | `testorder.coverage.threshold` | `2` | Minimum number of exercising tests for a class to count as well-tested |
| `outputDir` | `testorder.coverage.outputDir` | `target/coverage-reports` | Report output directory |

## Structural Change Analysis

Beyond simple file-level change detection, test-order performs **structural diff analysis** that parses Java sources at the method/field level.

Two parser backends are available:

| Backend | Property value | Description |
|---|---|---|
| **Island** (default) | `island` | Fast regex-based parser, no extra dependencies |
| **JavaParser** | `javaparser` | Full AST-based parser, requires `com.github.javaparser:javaparser-core` on classpath |

To disable:

```bash
mvn test -Dtestorder.structuralDiff.enabled=false
```

## Java Agent (Manual Usage)

The agent can be attached manually:

```bash
java -javaagent:test-order-agent.jar=outputDir=target/test-order-deps,includePackages=com.example \
     -jar your-test-runner.jar
```

| Option | Default | Description |
|---|---|---|
| `outputDir` | `target/test-order-deps` | Directory for `.deps` files |
| `includePackages` | — | Semicolon-separated package prefixes to instrument |
| `mode` | `CLASS` | `CLASS`, `METHOD`, or `MEMBER` |

## CLI Tool

The `test-order-core` module includes a CLI tool:

```bash
java -jar test-order-core-jar-with-dependencies.jar <command>
```

### Commands

- `aggregate <depsDir>` — merge `.deps` files into an index
- `affected <indexFile> -c <classes>` — list tests affected by changed classes
- `stats <indexFile>` — print index statistics
- `dump <indexFile>` — dump a binary index in human-readable text format
- `export-json <indexFile> [-o deps.json]` — export the binary index as JSON
- `optimize [stateFile]` — optimise scoring weights via genetic algorithm
- `select <indexFile>` — select a fast subset of tests
- `hash-snapshot` — scan source tree and save LZ4-compressed file hashes
- `changed` — detect changed source files (supports `--mode`)
- `run <indexFile>` — detect changes and print affected tests
- `struct-diff` — structural diff of Java files (types, methods, fields) against git
- `advise <indexFile>` — analyse per-method dependency overlap and suggest test classes to split
