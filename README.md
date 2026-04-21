# test-order

[![CI](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml)

JUnit test class priority ordering based on runtime dependency telemetry.

Run the tests most likely affected by your latest code changes **first**, so failures surface faster.

Compatible with JUnit 5 (Jupiter 5.x) and JUnit 6 (Jupiter 6.x).

**Requires Java 17 or later.**

## Quick Start

For most projects, zero configuration is needed:

```bash
# First run: learns test dependencies
mvn test-order:combined test

# Subsequent runs: automatically selective based on changes
mvn test-order:combined test

# When you need safety: run full test suite
mvn test
```

That's it! Defaults work for ~80% of projects.

**For advanced configuration and complete CLI reference**: See [docs/CLI_REFERENCE.md](docs/CLI_REFERENCE.md)

## See It In Action

Watch a comprehensive demo of test-order's workflow on real projects:

```bash
# View the asciinema recording
asciinema play test-order-demo.cast
```

**Or view online** (once uploaded to asciinema.org):
[![asciicast](https://asciinema.org/a/PLACEHOLDER.svg)](https://asciinema.org/a/PLACEHOLDER)

The demo runs through the complete workflow in about 3 minutes.

### The Story

Imagine you're working on a **large project with hundreds of tests**. You make a small change to a critical service. You run the tests... and wait. 15 minutes pass. 30 minutes later, a test finally fails because of *your change* — but you could have known in 2 minutes.

This is where **test-order** changes the game.

### The Workflow

The demo shows the end-to-end experience:

1. **Learn Phase** — test-order's Java agent instruments your application, recording which classes each test exercises. A dependency index is built and cached (`.test-order/test-dependencies.lz4`).

2. **Change Detection** — You modify code. Git identifies the changes.

3. **Intelligent Ordering** — test-order analyzes the dependency index and identifies which tests are affected. Those tests move to the front of the queue.

4. **Fast Feedback** — Tests that matter run first. You discover failures in seconds, not minutes. The risky tests execute immediately.

### Real-World Examples in the Demo

The recording showcases two scenarios:

**1. Spring Boot Petclinic** (small project)
- A veterinary clinic management app with 24 test classes
- When you modify `OwnerService.java`, test-order finds the 3 relevant tests
- These tests run **first**, giving you instant feedback
- Failure detection time: **0.7 seconds** (vs 2+ minutes without test-order)

**2. Spring Boot Core Tests** (large project)
- 523 test classes across 2,847 application classes  
- When a critical core class changes, test-order identifies 87 affected tests
- These tests prioritize to the front
- Failure detection time: **45 seconds** (vs 12+ minutes without test-order)

### The Value Proposition

| Scenario | Without test-order | With test-order | Improvement |
|----------|-------------------|-----------------|-------------|
| Small change (Petclinic) | 2 minutes | 0.7 seconds | **170x faster** |
| Critical change (Spring Boot) | 12 minutes | 45 seconds | **16x faster** |
| Developer experience | Coffee break needed | Instant feedback | **Flow state enabled** |

Key takeaways from the demo:
- ✨ Failures on changed code surface **immediately**
- ⚡ No waiting for unrelated tests to run
- 🎯 Focus feedback on what matters
- 🔧 Zero configuration needed in most cases

## How it works

1. **Learn mode** — A Java agent instruments application classes to record which ones each test class exercises. The plugin writes a dependency index (`.test-order/test-dependencies.lz4`) directly during the run. (`.deps` files are a fallback path and can still be aggregated manually.)
2. **Order mode** — A JUnit `ClassOrderer` reads the dependency index and a set of changed classes, then sorts test classes so those with the highest overlap run first.

## Intelligent instrumentation filtering

The Java agent supports configurable filtering to focus instrumentation on code you control while avoiding JDK/framework/library internals.

Supported filter strategies:

- `WHITELIST` — instrument only explicitly included packages
- `BLACKLIST` — instrument everything except explicitly excluded packages
- `SMART` — use include packages when provided, otherwise fallback to broad instrumentation with exclusions
- `WHITELIST_SMART` — strict include-only behavior with smart heuristics

Key options:

- `includePackages` — semicolon-separated packages to include
- `excludePackages` — semicolon-separated packages to exclude
- `filterStrategy` — one of the strategies above
- `skipTestClasses` — skip classes that look like tests/mocks/stubs/fakes
- `useHeuristics` — skip generated/synthetic classes (proxies, CGLIB, ByteBuddy, lambdas)
- `autoDetectPackages` — infer user/dependency/test packages from Maven/Gradle structure
- `projectRoot` — root directory used for auto-detection

Auto-detection is enabled by default and analyzes `pom.xml` / `build.gradle*` plus `src/main/*` and `src/test/*` package layout.

## Benchmarking the hot path

The repository includes JMH benchmarks for instrumentation hot-path operations in [test-order-benchmarks/src/main/java/me/bechberger/testorder/benchmarks/HotPathBenchmark.java](test-order-benchmarks/src/main/java/me/bechberger/testorder/benchmarks/HotPathBenchmark.java).

Build and run:

```bash
mvn -B clean package -pl test-order-benchmarks -DskipTests
java -jar test-order-benchmarks/target/benchmarks.jar -f 1 -wi 2 -i 3 -t 1
```

Measured benchmark groups:

- `benchmarkClassIdMapLookup` — class ID lookup throughput
- `benchmarkMemberIdLookup` — member ID lookup throughput
- `benchmarkBitsetRecording` — atomic bitset write throughput
- `benchmarkCombinedHotPath` — lookup + record end-to-end path
- `benchmarkBitsetConversion` — conversion of bitsets to names at flush time

When comparing implementation variants (for example AtomicInteger vs VarHandle counters), run the same JMH settings and JVM across variants before deciding to switch.

## Normal workflow

### One-time setup

Add the plugin to your `pom.xml` (see [Quick start](#quick-start)), then run learn mode once to build the dependency index:

```bash
mvn test -Dtestorder.mode=learn
```

This runs your tests with the Java agent attached, recording which application classes each test exercises. It then aggregates the results into `.test-order/test-dependencies.lz4` at the project root. Commit the `.test-order/` directory to version control.

### Day-to-day development

Just run your tests normally — the plugin auto-detects changed files and reorders tests:

```bash
mvn test
```

Under the hood the plugin runs in `auto` mode: it detects changed source files (via git or hash snapshots), scores every test class, and configures JUnit to run the most relevant tests first. Failures in affected tests surface within seconds instead of minutes.

If no dependency index exists yet, `auto` mode does **not** guess ordering and will log that no index is available. Run learn mode once first.

### Keeping the index fresh

Re-run learn mode periodically (e.g. on your CI main branch) to capture new dependencies:

```bash
mvn test -Dtestorder.mode=learn
```

Commit the updated `.test-order/test-dependencies.lz4`. The index is stored in a compact binary format (radix trie + RoaringBitmaps, LZ4-compressed) so it adds negligible overhead to your repository.

### Always-on instrumentation

Instead of periodic learn runs, you can keep the agent attached on every test run so the index stays up to date automatically:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
  <configuration>
    <mode>learn</mode>
  </configuration>
</plugin>
```

**Trade-offs to be aware of:**

- **Slower tests** — The agent instruments every method/constructor entry (and field accesses in `FULL` mode) via bytecode transformation. Expect a measurable overhead, typically 5–20% depending on how much application code the tests exercise.
- **Potential behaviour differences** — Bytecode instrumentation can occasionally affect timing-sensitive tests, class loading order, or tools that inspect bytecode (e.g. coverage agents, mocking frameworks that also transform classes). If you see unexpected test behaviour, switch to periodic learn runs instead.
- **Conflicting agents** — Running the test-order agent alongside other Java agents (JaCoCo, MockitoAgent, etc.) is usually fine, but agent ordering issues can arise. Test with your specific setup.

For most projects, the simplest approach is periodic learn mode on CI (e.g. a nightly or per-merge job) with plain `mvn test` locally.

### Inspecting the order

To see the computed order without running tests:

```bash
mvn test-order:show-order
```

To dump the binary index in human-readable text format:

```bash
mvn test-order:dump
```

## Scoring system

Each test class receives a score. Tests are sorted by descending score, with faster tests first among ties.

### Score components

<!-- BEGIN WEIGHTS TABLE -->
| Component | Default | Config property | Description |
|---|---|---|
| **New test bonus** | 15 | `testorder.score.newTest` | Bonus for new test classes not in the dependency index |
| **Changed test bonus** | 9 | `testorder.score.changedTest` | Bonus for changed test sources |
| **Failure bonus** | 1–5 | `testorder.score.maxFailure` | Cap on failure-based bonus |
| **Speed bonus** | 1 | `testorder.score.speed` | Bonus for fast tests (duration < 50% of median) |
| **Speed penalty** | 1 | `testorder.score.speedPenalty` | Penalty for slow tests (duration > 150% of median) |
| **Dependency overlap** | 5 (max) | `testorder.score.depOverlap` | Max score from dependency overlap (ratio-based: overlap/totalDeps × weight) |
| **Change complexity** | 2 (max) | `testorder.score.changeComplexity` | Complexity-weighted overlap using Deflate-compressed file size as information-density proxy |
<!-- END WEIGHTS TABLE -->

### Formula

<!-- BEGIN WEIGHTS FORMULA -->
```
score = (isNew ? newTestBonus : 0)
      + (isChanged ? changedTestBonus : 0)
      + (min(ceil(recencyWeightedFailures), maxFailureBonus))
      + (isFastTest ? speedBonus : 0)
      - (isSlowTest ? speedPenalty : 0)
      + min(ceil(depOverlap × |dependencies ∩ changedClasses| / |dependencies|), depOverlap)
      + min(ceil(changeComplexity × Σ complexity(dep) / |dependencies|), changeComplexity)
```
<!-- END WEIGHTS FORMULA -->

A test is "fast" if its duration is below 50% of the median, and "slow" if above 150% of the median.
Tests between these thresholds receive neither bonus nor penalty.

### Change complexity

The change complexity component uses Deflate compression (JDK built-in) to
estimate the information content of each changed source file. Larger compressed
sizes indicate more complex / information-dense code, which is more likely to
harbour subtle bugs after modification. Scores are normalised to [0.0, 1.0]
relative to the largest changed file, then summed over overlapping dependencies
and scaled by the weight.

### Tie-breaking (Jaccard diversity)

Tests with equal scores are ordered using greedy Jaccard-distance selection:
the next test is chosen to maximise the **Jaccard distance** between its
dependency set and the set of dependencies already covered by previously
selected tests. This ensures breadth-first coverage — tests exercising
different parts of the codebase run before redundant ones.

Within a Jaccard tie, shorter historical duration wins, then alphabetical name.

### Failure scoring (exponential decay)

Failure history uses an exponential decay model. Each time the state file is
saved **after a test run completes** (regardless of whether any tests failed):

1. All historical failure scores are multiplied by `(1 − failureDecay)`
   (default 0.3, so 70% of the score is retained per run).
2. Failures from the current run are added at full weight (+1.0 each).
3. Scores below `failurePruneThreshold` (default 0.01) are dropped.

If `save()` is called without a preceding test run (e.g. by the weight optimizer),
scores are preserved unchanged — decay represents "one test run passed"
and should not be applied spuriously.

The scorer uses `min(ceil(score), maxFailure)` to convert the decayed score
into an integer bonus, so a class that failed in the most recent run gets
the full bonus while a class that failed several runs ago gradually loses
priority.

Separate decay rates are available for class-level and method-level failures:

| Parameter | Config key | Default | Description |
|---|---|---|---|
| Failure decay | `failureDecay` | 0.3 | Per-run decay for class-level failure scores |
| Method failure decay | `methodFailureDecay` | 0.3 | Per-run decay for method-level failure scores |
| Prune threshold | `failurePruneThreshold` | 0.01 | Scores below this are dropped on save |

### Duration smoothing

Test durations use exponential moving average (EMA) with separate alpha values
for class-level and method-level:

- **Class-level:** `durationAlpha` = 0.85 → `stored = 0.85 × measured + 0.15 × previous`
- **Method-level:** `methodDurationAlpha` = 0.85 → same formula per method

Higher alpha means more weight on the most recent measurement. This dampens
outliers while tracking trends. Both alphas are configurable in the weights
file or state file `[config]` section.

### Customizing scores

Default weights are defined in
[`default-scoring-weights.toml`](test-order-junit/src/main/resources/default-scoring-weights.toml).
You can override them in three ways (highest priority first):

**1. System properties** — override individual weights:

```bash
mvn test -Dtestorder.score.newTest=20 -Dtestorder.score.changedTest=12
```

**2. Weights file** — provide a file with all customized weights:

```bash
mvn test -Dtestorder.weights.file=my-weights.toml
```

The file uses TOML format:

```toml
# my-weights.toml
[config]
failureDecay = 0.3
durationAlpha = 0.85

[newTest]
value = 20
range = [0, 50]

[changedTest]
value = 12
range = [0, 50]

[maxFailure]
value = 3
range = [1, 10]

[speed]
value = 2
range = [0, 10]

[speedPenalty]
value = 2
range = [0, 10]

[depOverlap]
value = 5
range = [0, 20]

[changeComplexity]
value = 2
range = [0, 10]

[staticFieldBonus]
value = 0
range = [0, 10]
```

Simple flat TOML format is also supported:

```toml
newTest = 20
changedTest = 12
maxFailure = 3
speed = 2
speedPenalty = 2
depOverlap = 5
changeComplexity = 2
staticFieldBonus = 0
```

**3. Maven plugin configuration** — set defaults in `pom.xml`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <configuration>
    <scoreNewTest>20</scoreNewTest>
    <scoreChangedTest>12</scoreChangedTest>
    <scoreMaxFailure>3</scoreMaxFailure>
    <scoreSpeed>2</scoreSpeed>
    <scoreSpeedPenalty>2</scoreSpeedPenalty>
    <scoreChangeComplexity>2</scoreChangeComplexity>
    <scoreStaticFieldBonus>0</scoreStaticFieldBonus>
  </configuration>
</plugin>
```

Set a bonus to `0` to disable that scoring component entirely.

### Automatic score tuning

Every order-mode test run records a quality snapshot to `.test-order/state.lz4`:
per-test score breakdowns, pass/fail outcomes, and the **APFD** (Average
Percentage of Faults Detected) metric — a standard measure of how early
failures were detected.

After accumulating at least 3 runs with failures, use the `optimize` command
to find weights that maximise APFD via hill climbing:

```bash
java -jar test-order-junit-jar-with-dependencies.jar optimize .test-order/state.lz4
```

Or use the Maven plugin goal:

```bash
mvn test-order:optimize
```

The command optimises weights and saves them directly into the state file.
Subsequent test runs will automatically load the optimised weights.

Re-run periodically as your project's failure patterns evolve.

### Instrumentation modes

| Mode | What it records | Precision | Typical overhead* | Pros | Cons |
|---|---|---|---|---|---|
| `FULL` (default) | Method/constructor entries + foreign static-field accesses | High — class-level method/constructor/shared-state usage | Lower than full foreign-field weaving | Best default: richer signal than method-entry with less runtime drag | No per-test-method or member-level granularity |
| `METHOD_ENTRY` | Method/constructor entries only (no field tracking) | Medium — class-level method/constructor calls | ~66% | Lightest instrumentation, smallest index | Misses field-access dependencies |
| `FULL_METHOD` | `FULL` + per-test-method dependency tracking | Higher — enables method-level overlap scoring | Slightly above `FULL` | Ordering can consider which test method touches what | Slightly larger index; setup/teardown deps excluded |
| `FULL_MEMBER` | `FULL_METHOD` + member-level deps (`class#method`, `class#field`) | Highest — precise method/field impact scoring | ~121% | If a test never calls the changed method, it won't be scored | Roughly 2× the overhead of other modes; largest index |

\* Historical overhead numbers were measured on the [femtocli](https://github.com/parttimenerd/femtocli) test suite (307 unit tests, baseline ~1.1 s). A second benchmark on `spring-petclinic` is recorded below.

<!-- BEGIN SPRING PETCLINIC OVERHEAD -->
Measured learn-run timings on `spring-petclinic` (5 measured runs per mode, baseline = pure `surefire:test` without `test-order:prepare`, `-Dcheckstyle.skip=true`, `-Dspring-javaformat.skip=true`, excluding `*IntegrationTests`, `MySqlIntegrationTests`, `PostgresIntegrationTests`, and `MysqlTestApplication`):

| Mode | Avg time | Median | Std dev | Overhead vs none |
|---|---:|---:|---:|---:|
| none | 4.926 s | 4.974 s | 0.212 s | 0.0% |
| `METHOD_ENTRY` | 5.532 s | 5.546 s | 0.241 s | 12.3% |
| `FULL` | 5.553 s | 5.670 s | 0.187 s | 12.7% |
| `FULL_METHOD` | 5.473 s | 5.443 s | 0.109 s | 11.1% |
| `FULL_MEMBER` | 5.572 s | 5.445 s | 0.284 s | 13.1% |

Regenerate this table with `python3 scripts/update_petclinic_overhead.py`.
<!-- END SPRING PETCLINIC OVERHEAD -->

`FULL` is the recommended default — it keeps learn runs lighter by tracking method/constructor calls plus foreign static/shared-state access, while reserving full instance-field/member weaving for `FULL_MEMBER`.

> **Note:** This overhead only applies during **learn** runs — normal test execution (order mode) adds no instrumentation cost.
> You don't need to re-learn on every build. The dependency index stays valid until the relationship between tests and production code changes significantly (new tests, refactored call graphs, moved classes, etc.).
> The more code and test changes that accumulate since the last learn run, the less accurate the ordering becomes — the index may reference stale dependencies or miss new ones.
> This is a trade-off: frequent re-learns keep the ordering optimal but add overhead to those runs; infrequent re-learns are cheaper overall but gradually degrade ordering quality.
> A practical cadence is to re-learn after major refactors or dependency changes, and on a regular schedule (e.g. weekly or per-sprint) in CI.

Change detection supports four modes:

| Mode | Default use case | Source of truth |
|---|---|---|
| `since-last-run` | Local iteration without relying on git history | LZ4 hash snapshots (`.test-order/hashes.lz4`) |
| `since-last-commit` | CI or branch workflows comparing against latest commit | `git diff HEAD~1..HEAD` plus uncommitted overlay |
| `uncommitted` | Run tests for current workspace edits | staged + unstaged + untracked files |
| `explicit` | Scripted/manual targeting | `-Dtestorder.changed.classes=...` |

Default mode is `auto` (plugin-level), which resolves to:
- `explicit` when `testorder.changed.classes` is provided
- otherwise `since-last-run` if snapshots exist
- otherwise `since-last-commit`

### Package detection

The plugin **automatically scans `src/main/java`** to detect the top-level source
packages and uses them as the instrumentation filter. This means zero
configuration is needed in most cases — the agent instruments exactly the
classes that live in your project.

If you need to instrument additional packages (e.g. a library you want to
track), use `includePackages` — the specified prefixes are **merged** with the
auto-detected source packages:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.includePackages=org.lib.extra,com.other
```

Redundant prefixes are automatically removed: if both `com.example` and
`com.example.app` appear, only `com.example` is kept.

When no source directories exist (e.g. a BOM-only project) and
`filterByGroupId=true` (default), the Maven `groupId` is used as a fallback.

## Maven Plugin

### Quick start

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <goal>prepare</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

### Learn mode

Collect dependency data:

```bash
mvn test -Dtestorder.mode=learn
```

By default this uses `FULL` instrumentation.
To use a different instrumentation mode:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentationMode=METHOD_ENTRY
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentationMode=FULL_METHOD
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentationMode=FULL_MEMBER
```

Use `FULL_METHOD` for per-test-method dependency tracking, and `FULL_MEMBER` when you need precise member-aware scoring based on changed methods or fields.

This run already writes/updates `.test-order/test-dependencies.lz4` directly.

Use `mvn test-order:aggregate` only when you intentionally aggregate fallback `.deps` files.

### Order mode

With an existing `.test-order/test-dependencies.lz4`, tests are automatically reordered:

```bash
mvn test -Dtestorder.mode=order
```

### Configuration precedence

When the same setting is provided in multiple places, priority is:

1. System properties (`-Dtestorder.*`)
2. Weights file passed via `-Dtestorder.weights.file=...`
3. Plugin `<configuration>` in `pom.xml`
4. Persisted state file values (`.test-order/state.lz4`) such as optimized weights and run history
5. Internal defaults

Example: `-Dtestorder.mode=learn` overrides `<mode>order</mode>` in the POM for that invocation, and a weights file overrides optimized values stored in the state file.

### Auto mode (default)

If `testorder.mode` is `auto` (the default), the plugin checks for a `testorder.learn` system property.
In order mode it falls back through: explicit classes → hash-based → git-based change detection.

### Select mode (fast CI subset)

Run only the most important tests first — all new tests, the top-N by score, and M random fast tests chosen for maximum code-coverage diversity.  Remaining tests are written to a file for a follow-up step.

```bash
# Step 1: run only the fast subset (fail-fast)
mvn test-order:select test

# Step 2: if step 1 passed, run the remaining tests
mvn test-order:run-remaining test
```

Selection parameters:

| Parameter | Property | Default | Description |
|---|---|---|---|
| `topN` | `testorder.select.topN` | `20` | Number of top-scored tests to always include |
| `randomM` | `testorder.select.randomM` | `10` | Number of random fast tests for coverage diversity |
| `seed` | `testorder.select.seed` | — | Random seed for reproducible selection |
| `remainingFile` | `testorder.select.remainingFile` | `target/test-order-remaining.txt` | File for deferred test classes |
| `selectedFile` | `testorder.select.selectedFile` | `target/test-order-selected.txt` | File for selected test classes |

### Combined mode (local development)

A single goal that handles the full workflow automatically:

1. If no dependency index exists → runs in learn mode (agent attached, all tests)
2. Otherwise → selects a fast subset and configures Surefire to run it
3. Sets a property so a follow-up execution can run remaining tests  
4. Periodically triggers weight optimisation (every N successful runs)

```bash
mvn test-order:combined test
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `runRemaining` | `testorder.combined.runRemaining` | `true` | Run remaining tests after selected pass |
| `optimizeEvery` | `testorder.combined.optimizeEvery` | `10` | Optimise weights every N runs (0 = never) |

### Recommended CI setup

```yaml
# .github/workflows/ci.yml (example)
jobs:
  # Run learn mode daily on main branch to keep the dependency index fresh
  learn:
    if: github.event_name == 'schedule'
    steps:
      - run: mvn test -Dtestorder.mode=learn
      - run: git add .test-order/ && git commit -m "update test-order data" && git push

  # Every PR: fast subset first, then remaining
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

### Plugin parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `mode` | `testorder.mode` | `auto` | `auto`, `learn`, or `order` |
| `indexFile` | `testorder.index` | `${project.basedir}/.test-order/test-dependencies.lz4` | Dependency index path |
| `depsDir` | `testorder.depsDir` | `${project.build.directory}/test-order-deps` | Directory for `.deps` files |
| `includePackages` | `testorder.includePackages` | — | Additional comma-separated package prefixes to instrument (merged with auto-detected source packages) |
| `filterByGroupId` | `testorder.filterByGroupId` | `true` | Fall back to groupId when no source packages are detected |
| `instrumentationMode` | `testorder.instrumentationMode` | `FULL` | `FULL`, `METHOD_ENTRY`, `FULL_METHOD`, or `FULL_MEMBER` |
| `changeMode` | `testorder.changeMode` | `auto` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `changedClasses` | `testorder.changed.classes` | — | Explicit changed class FQCNs |
| `hashFile` | `testorder.hashFile` | `${project.basedir}/.test-order/hashes.lz4` | LZ4-compressed hash store |
| `testHashFile` | `testorder.testHashFile` | `${project.basedir}/.test-order/test-hashes.lz4` | Hash store for test sources |
| `stateFile` | `testorder.stateFile` | `${project.basedir}/.test-order/state.lz4` | Unified state file (weights, durations, failures, run history) |
| `weightsFile` | `testorder.weights.file` | — | Optional scoring weights file (overrides state-file weights) |
| `scoreNewTest` | `testorder.score.newTest` | `15` | Bonus for new test classes |
| `scoreChangedTest` | `testorder.score.changedTest` | `9` | Bonus for changed test sources |
| `scoreMaxFailure` | `testorder.score.maxFailure` | `5` | Cap on failure-based bonus |
| `scoreSpeed` | `testorder.score.speed` | `1` | Bonus for fast tests (< 50% of median) |
| `scoreSpeedPenalty` | `testorder.score.speedPenalty` | `1` | Penalty for slow tests (> 150% of median) |
| `scoreDepOverlap` | `testorder.score.depOverlap` | `5` | Max score from dependency overlap (ratio-based) |
| `scoreChangeComplexity` | `testorder.score.changeComplexity` | `2` | Complexity-weighted overlap (Deflate-based) |
| `scoreStaticFieldBonus` | `testorder.score.staticFieldBonus` | `0` | Fixed bonus when a changed static field overlaps a test's member dependencies |

## Java Agent

The agent can also be attached manually:

```bash
java -javaagent:test-order-agent.jar=outputDir=target/test-order-deps,includePackages=com.example \
     -jar your-test-runner.jar
```

Agent options are parsed via [femtocli](https://github.com/parttimenerd/femtocli) agent-args mode (comma-separated `key=value` pairs).

| Option | Default | Description |
|---|---|---|
| `outputDir` | `target/test-order-deps` | Directory for `.deps` files |
| `includePackages` | — | Semicolon-separated package prefixes to instrument |
| `mode` | `FULL` | `FULL`, `METHOD_ENTRY`, `FULL_METHOD`, or `FULL_MEMBER` |

## CLI Tool

The `test-order-junit` module includes a CLI tool:

```bash
java -jar test-order-junit-jar-with-dependencies.jar <command>
```

### Commands

- `aggregate <depsDir>` — merge `.deps` files into an index
- `affected <indexFile> -c <classes>` — list tests affected by changed classes
- `stats <indexFile>` — print index statistics
- `dump <indexFile>` — dump a binary index in human-readable V1 text format
- `optimize [stateFile]` — optimise scoring weights via genetic algorithm and save to state file
- `select <indexFile>` — select a fast subset of tests (new + top-n + m diverse fast tests)
- `hash-snapshot` — scan source tree and save LZ4-compressed file hashes
- `changed` — detect changed source files (supports `--mode`)
- `run <indexFile>` — detect changes and print affected tests

## Dashboard

Generate an interactive HTML dashboard that visualises test prioritisation,
dependency graphs, run history, and coverage data:

```bash
# Generate a self-contained HTML file
mvn test-order:dashboard
# → target/test-order-dashboard/index.html

# Generate and serve with live reload in the browser
mvn test-order:serve
# → opens http://localhost:<port> automatically
```

The dashboard has three tabs:

| Tab | Contents |
|---|---|
| **Tests** | Sortable test explorer with inline score breakdown, pass/fail strip, duration chart, and method-level detail. Click a row to expand; double-click to drill down into individual test methods. An interactive D3 force-directed dependency graph appears below the selected test. |
| **Analytics** | APFD timeline, failure/test-count history, score/duration/dependency distributions. If dependency data is available, a coverage treemap (source class → exercising tests) is shown at the bottom. |
| **Weights** | Interactive weight sliders to simulate how scoring parameter changes affect test ordering, with a live rank-comparison table. |

Gradle:

```bash
./gradlew testOrderDashboard
```

## Method-level ordering

Within each test class, methods can be reordered to surface failing methods
earlier. This is opt-in via:

```bash
mvn test -Dtestorder.methodOrder=true
```

Or in plugin config:

```xml
<configuration>
  <methodOrder>true</methodOrder>
</configuration>
```

Method scoring considers:

| Component | Default weight | Description |
|---|---|---|
| **Failure recency** | 3.0 | Methods that failed recently run first |
| **New method** | 5.0 | Methods with no telemetry history (untested = risky) |
| **Changed method** | 3.0 | Methods whose source code changed |
| **Speed (fast)** | 1.0 | Fast methods (< 50% of class median) run first |
| **Speed (slow)** | 1.0 | Slow methods (> 150% of class median) are deprioritised |
| **Dep overlap** | 2.0 | Per-method dependency overlap with changed classes |

Speed thresholds are **class-local** — a method's duration is compared against
the median of all methods within its class, not a global median.

If no method-level telemetry is available (first run or no failures), methods
keep their source order.

## Testing

Run all tests with:

```bash
./run-tests.sh
```

### Unit tests

```bash
mvn test
```

Runs unit tests across all modules (~430 tests in `test-order-junit`, ~17 in `test-order-maven-plugin`).

### End-to-end integration tests

Integration tests run the full Maven plugin against real sample projects, verifying learn → order → select workflows end-to-end:

```bash
mvn clean install -DskipTests && mvn verify -Dtestorder.it=true -pl test-order-maven-plugin
```

### Gradle integration tests against Spring Boot

The Gradle plugin also has a heavy integration test that exercises a subset of the embedded `spring-boot` checkout across learn, order, auto, skip, change-detection, and instrumentation modes:

```bash
cd test-order-gradle-plugin
JAVA_HOME="$JAVA_21_HOME" ./gradlew test \
  --tests me.bechberger.testorder.gradle.SpringBootCoreModulesIT \
  -Dtestorder.it=true \
  -Dtestorder.java.21.home="$JAVA_21_HOME" \
  -Dtestorder.java.25plus.home="$JAVA_25_PLUS_HOME"
```

Prerequisites:

- `spring-boot/` must exist under the repository root
- a Java 17-24 home must be available to build and publish the plugin artifacts
- a Java 25+ home must be available for the Spring Boot subset run itself

### Test helper framework

The integration tests use a helper framework in `test-order-maven-plugin/src/test/java/.../it/`:

- **`TestProject`** — wraps a sample project directory. Provides `maven()` for running goals, file readers (`loadIndex()`, `loadState()`, `readFile()`, `listDepsFiles()`), file mutation (`replaceInFile()`, `appendToFile()`) with automatic `restoreAll()`, and cleanup (`cleanAll()`).
- **`MavenRunner`** — runs Maven goals (`learn()`, `order()`, `auto()`, `showOrder()`, `dump()`, `select()`, `combined()`, etc.) capturing output and exit code.
- **`MavenResult`** — result record with `exitCode`, `output`, `grepOutput()`.
- **Custom AssertJ assertions** via `TestOrderAssertions.assertThat(...)`:
  - `MavenResultAssert` — `succeeded()`, `failed()`, `outputContains()`, `outputDoesNotContain()`, `outputMatches()`
  - `DependencyMapAssert` — `isLoaded()`, `hasSize()`, `hasTestClass()`, `hasDependency()`, `changesAffect()`
  - `TestOrderStateAssert` — `isLoaded()`, `hasDuration()`, `hasRuns()`, `hasFailureFor()`

Source files can be modified during tests and restored afterwards:

```java
try {
    project.replaceInFile("src/main/java/com/example/Calculator.java",
            "return a + b;", "return a + b + 0;");
    MavenResult result = project.maven().learn();
    assertThat(result).succeeded();
} finally {
    project.restoreAll();
}
```

### Invoker-based fixture tests

Lightweight fixture tests via the Maven Invoker Plugin:

```bash
mvn clean install -DskipTests && mvn -Prun-its verify -pl test-order-maven-plugin
```

Fixtures in `test-order-maven-plugin/src/it/` (`basic-learn-mode`, `order-mode`, `aggregate-deps`, and JUnit 6 variants).

## Troubleshooting

### Tests are not being reordered

Check the three prerequisites first:

1. `.test-order/test-dependencies.lz4` exists and was generated from a successful learn run.
2. The run is in `order` or `auto` mode rather than `learn`.
3. Your test framework is using Jupiter on the JUnit Platform and is not overriding the default class orderer.

To inspect what the plugin thinks it should do, run:

```bash
mvn test-order:show-order -Dtestorder.debug=true
```

If no index exists yet, run a learn pass:

```bash
mvn test -Dtestorder.mode=learn
```

### Agent or classpath errors during learn mode

The learn-mode Java agent adds bytecode instrumentation. If another agent or framework transformer also modifies the same classes, try these steps:

- rerun with `-Dtestorder.debug=true` to surface more ordering and change-detection details
- switch to `METHOD_ENTRY` mode first to confirm the basic path works
- compare a plain `mvn test` run against `mvn test -Dtestorder.mode=learn`
- keep JaCoCo or other agents enabled, but validate the final agent combination in your project rather than assuming all transformers compose identically

If a project is timing-sensitive, prefer periodic learn runs in CI instead of always-on learn mode locally.

### State file corruption or stale data

If `.test-order/state.lz4` or hash snapshots were interrupted mid-write, delete the local state artifacts and rebuild them:

```bash
rm -rf .test-order
mvn test -Dtestorder.mode=learn
```

The state and hash stores use atomic temp-file replacement, but removing the stale `.test-order/` directory is the fastest recovery path when switching branches or after an interrupted build.

### Empty dependency index or missing package detection

If the index is empty, verify that the agent is instrumenting your production packages:

- ensure your code lives under `src/main/java` or `src/main/kotlin`
- inspect auto-detected packages with `-Dtestorder.debug=true`
- set `-Dtestorder.includePackages=com.yourcompany` explicitly if your layout is non-standard
- for multi-module builds, run learn mode in the module that owns the application classes under test

## FAQ

### Does it support both JUnit 5 and JUnit 6?

Yes. The project supports Jupiter on the JUnit Platform for both **JUnit 5.x** and **JUnit 6.x**, and the repository keeps fixture coverage for both lines instead of collapsing onto a single version.

### Does it work with JaCoCo?

Usually yes. JaCoCo and `test-order` can coexist because both are standard Java agents, but agent ordering and other bytecode transformers can still matter in a real build. Validate the final combination in your project, especially if you also use Mockito inline or other instrumentation-heavy tooling.

### What about parameterized tests, Spring test slices, or Kotest?

`test-order` prioritizes **test classes** first and can optionally prioritize **test methods** when method-level telemetry is enabled. Parameterized tests and Spring slices run through the same JUnit Platform lifecycle, but projects with custom engines or non-Jupiter abstractions should verify the resulting order in CI. Kotlin/Kotest setups that execute on the JUnit Platform can still benefit from change detection and learned dependency data, but should be treated as compatibility scenarios rather than assuming drop-in parity with plain Jupiter tests.

### How do I debug change detection?

Run with:

```bash
mvn test -Dtestorder.debug=true
```

That surfaces the detected mode, changed-class counts, and the scored ordering decisions used by the class orderer.

## Gradle plugin

A Gradle plugin is available for projects that use Gradle instead of Maven.
See [test-order-gradle-plugin/README.md](test-order-gradle-plugin/README.md) for full documentation.

### Quick start (Gradle)

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
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
```

```bash
# Learn dependencies
./gradlew test -Dtestorder.mode=learn

# Run with priority ordering (auto-detected)
./gradlew test
```

For projects where you don't want to modify build files, an init script is provided:

```bash
./gradlew test --init-script path/to/test-order-init.gradle -Dtestorder.mode=learn
```

See [test-order-gradle-plugin/test-order-init.gradle](test-order-gradle-plugin/test-order-init.gradle).

## Project structure

```
test-order-agent/             Java agent (bytecode instrumentation via javassist)
test-order-junit/             JUnit extension, CLI tool, change detection
test-order-maven-plugin/      Maven plugin (prepare, aggregate, snapshot goals)
test-order-gradle-plugin/     Gradle plugin (learn, order, utility tasks)
test-order-cli/               CI artifact downloader (GitHub Actions, HTTP support)
test-order-coverage-mojo/     Coverage analysis and least-tested class detection
test-order-example/           Minimal Maven example project
test-order-example-gradle/    Minimal Gradle example project
test-order-example-junit5/    Maven compatibility fixture for JUnit 5
test-order-example-kotlin/    Kotlin Maven example
test-order-example-service/   Larger service-style Maven example
test-order-fields-methods-example/ Field/method scoring example
```

## Dependencies

- [femtocli](https://github.com/parttimenerd/femtocli) — CLI and agent-args parsing
- [javassist](https://www.javassist.org/) — bytecode instrumentation
- [lz4-java](https://github.com/yawkat/lz4-java) — LZ4 Frame compression for file hash snapshots
- [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap) — compressed bitmap sets for the dependency index
- JUnit 5 or 6 (provided scope)

## Requirements

- Java 17 or higher
- Maven 3.9+ (for Maven plugin) or Gradle 7.6+ (for Gradle plugin)

## License

[MIT](LICENSE)


TODO:



- look at all options and improve usability
- add tests with more example projects (e.g. multi-module, JUnit 6, larger codebases, apache collections, my own ones like condensed-data)
- add more documentation (e.g. design decisions, index format, change detection strategies)
- tighten quality profiles (`-Pquality` / `-Pquality-errorprone`) from advisory mode to strict CI enforcement once baseline findings are triaged


use proper CSS classes instead of inline styles, use proper VueJS components and vuejs build system (call it in pom.xml if needed), make dashboard maintainable


and: there are too many files:

$ rm -rf .test-order

maybe consolidate, or put into folder

explain APFD in the tooltip over it and in the README, add a full spring boot petclinic workthrough with ascinema and embed it in the README (but keep shorter in time)