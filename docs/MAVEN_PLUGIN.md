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
- **5–20% overhead** from bytecode instrumentation on every run
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
| `test-order:show-order` | Print ranking and score breakdown |
| `test-order:show-method-order` | Print method-level priority order within each class |
| `test-order:dashboard` | Generate interactive HTML dashboard |
| `test-order:serve` | Serve dashboard via local HTTP server |
| `test-order:optimize` | Re-optimise scoring weights from run history |
| `test-order:snapshot` | Save source/test file hash snapshots |
| `test-order:aggregate` | Merge `.deps` files into dependency index |
| `test-order:dump` | Print dependency index contents |
| `test-order:export-json` | Export dependency index as JSON |
| `test-order:diagnose` | Run diagnostic health checks |
| `test-order:compact` | Compact the state file (remove old entries) |
| `test-order:clean` | Remove all test-order state, indexes, and hashes |
| `test-order:download` | Download dependency index from CI artifact store |
| `test-order:coverage` | Generate least-tested / coverage reports |
| `test-order:help` | Display all goals and common properties |

> `test-order:learn` only prepares learn mode — always pair it with the `test` phase to actually execute tests. Similarly, `test-order:select` configures Surefire but needs `test` to run the selected subset.

## Learn Mode

Collect dependency data:

```bash
mvn test -Dtestorder.mode=learn
```

By default this uses `FULL` instrumentation.
To use a different instrumentation mode:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=METHOD_ENTRY
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL_METHOD
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL_MEMBER
```

This run writes/updates `.test-order/test-dependencies.lz4` directly.

Use `mvn test-order:aggregate` only when you intentionally aggregate fallback `.deps` files.

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
| `topN` | `testorder.select.topN` | `-1` | Number of top-scored affected tests to include (`-1` = all affected) |
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
| `runRemaining` | `testorder.combined.runRemaining` | `true` | Automatically run remaining tests after the selected subset |
| `optimizeEvery` | `testorder.combined.optimizeEvery` | `10` | Optimise weights every N runs (0 = never) |
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
| `indexFile` | `testorder.index` | `${project.basedir}/.test-order/test-dependencies.lz4` | Dependency index path |
| `depsDir` | `testorder.depsDir` | `${project.build.directory}/test-order-deps` | Directory for `.deps` files |
| `includePackages` | `testorder.includePackages` | — | Additional comma-separated package prefixes to instrument |
| `filterByGroupId` | `testorder.filterByGroupId` | `true` | Fall back to groupId when no source packages are detected |
| `instrumentationMode` | `testorder.instrumentation.mode` | `FULL` | `FULL`, `METHOD_ENTRY`, `FULL_METHOD`, or `FULL_MEMBER` |
| `changeMode` | `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `changedClasses` | `testorder.changed.classes` | — | Explicit changed class FQCNs |
| `hashFile` | `testorder.hashFile` | `${project.basedir}/.test-order/hashes.lz4` | LZ4-compressed hash store |
| `testHashFile` | `testorder.testHashFile` | `${project.basedir}/.test-order/test-hashes.lz4` | Hash store for test sources |
| `stateFile` | `testorder.stateFile` | `${project.basedir}/.test-order/state.lz4` | Unified state file |
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
| `testorder.score.ema.varianceThreshold` | — | EMA variance threshold for duration stability |

### Runtime Properties

| Property | Description |
|---|---|
| `testorder.debug` | Enable verbose debug output for ordering and change detection |
| `testorder.project.root` | Git project root for change detection |
| `testorder.source.root` | Custom source root (overrides auto-detected `src/main/java`) |
| `testorder.history.maxRuns` | Maximum run history entries (default: 50) |
| `testorder.structuralDiff.enabled` | Enable/disable structural change analysis (default: true) |
| `testorder.changed.classes.file` | Read changed classes from a file |
| `testorder.changed.methods` | Explicit changed methods list |

## Coverage Analysis

The `coverage` goal analyses the dependency index to identify least-tested production classes:

```bash
mvn test-order:coverage
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `threshold` | `coverage.threshold` | `2` | Minimum number of exercising tests for a class to count as well-tested |
| `outputDir` | `coverage.outputDir` | `target/coverage-reports` | Report output directory |

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
| `mode` | `FULL` | `FULL`, `METHOD_ENTRY`, `FULL_METHOD`, or `FULL_MEMBER` |

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
