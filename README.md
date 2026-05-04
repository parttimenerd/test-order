# test-order

[![CI](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml)

Large test suites destroy developer momentum. A suite that once ran in minutes can bloat into an hour-long bottleneck, burying relevant failures somewhere in the middle of the output.

**test-order** is a zero-configuration Maven and Gradle plugin that uses runtime dependency telemetry to identify which tests exercise your modified code — then forces them to the **front of the execution queue**. Failures surface in seconds, not hours.

- No infrastructure to deploy (unlike [Drill4J](https://drill4j.github.io/))
- No source-code annotations required (unlike [Skippy](https://www.skippy.io/))
- Works with **JUnit 5** (Jupiter 5.x), **JUnit 6** (Jupiter 6.x), and **TestNG** (7.x+)

**Requires Java 17 or later.**

## Quick Start

For most projects, zero configuration is needed — just add the plugin and run your tests.

### Maven

Add the plugin to your `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>me.bechberger</groupId>
      <artifactId>test-order-maven-plugin</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </plugin>
  </plugins>
</build>
```

Then run:

```bash
# First run: learns test dependencies
mvn test-order:auto test

# Subsequent runs: automatically selective based on changes
mvn test-order:auto test

# When you need safety: run full test suite
mvn test
```

### Gradle

Add the plugin to your `build.gradle` (Groovy DSL):

```groovy
plugins {
    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
}
```

Or `build.gradle.kts` (Kotlin DSL):

```kotlin
plugins {
    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
}
```

Then run:

```bash
# Auto-detects changes, learns on first run, reorders on subsequent runs
./gradlew test

# Explicit learn mode
./gradlew test -Dtestorder.mode=learn

# Explicit order mode
./gradlew test -Dtestorder.mode=order
```

That's it! Defaults work for ~80% of projects.

**For advanced configuration and complete CLI reference**: See [docs/CLI_REFERENCE.md](docs/CLI_REFERENCE.md)

### Maven vs Gradle at a glance

| Feature | Maven | Gradle |
|---|---|---|
| Plugin application | `<plugin>` in `pom.xml` | `plugins { id 'me.bechberger.test-order' }` |
| Auto mode | `mvn test-order:auto test` | `./gradlew test` (auto-detected) |
| Learn mode | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` |
| Dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Export index JSON | `mvn test-order:export-json` | `./gradlew testOrderExportJson` |
| Optimise weights | `mvn test-order:optimize` | `./gradlew testOrderOptimize` |
| Select subset | `mvn test-order:select test` | `./gradlew testOrderSelect` |

## Fail Your Tests Fast

[![asciicast](https://asciinema.org/a/QhXjJtvug2nR2VVh.svg)](https://asciinema.org/a/QhXjJtvug2nR2VVh)

> *Fail your tests fast by using an intelligent test order.*

The recording above walks through the full workflow on **Spring Petclinic** — a
veterinary clinic app with ~24 test classes. In under 3 minutes you'll see:

### The Story

You touch `OwnerController.java` — one file in a project with dozens of tests.
Without test-order every test runs in the default order and the relevant
failures hide somewhere in the middle of the suite. With test-order, the tests
that exercise `OwnerController` jump to the **front of the queue** and fail
immediately.

### What the Demo Shows

1. **Learn** — A single `mvn test` run with the plugin collects a dependency
   index: which test class exercises which source class.

2. **Show order (baseline)** — Before any code change, `mvn test-order:show-order`
   lists tests scored by speed only.

3. **Modify `OwnerController`** — A one-line change. The diff is shown.

4. **Show order (after change)** — `OwnerControllerTests` jumps to rank 1;
   related tests (`VisitControllerTests`, …) are boosted too.

5. **Run prioritized** — `mvn test` executes tests in the new order.
   Owner-related tests run first, giving instant feedback on the change.

6. **Second change** — `VetController` is modified instead. The order shifts
   automatically: `VetControllerTests` moves to the top.

7. **History & Dashboard** — After several runs the plugin accumulates
   duration and failure history. `mvn test-order:dashboard` generates an
   interactive HTML report to explore scores, dependencies, and trends.

### Key Takeaways

- Tests affected by your change run **first** — failures surface in seconds
- No waiting for unrelated tests to finish before you see what broke
- Zero configuration needed: just add the plugin and run `mvn test`
- The dashboard gives full visibility into test scores and dependency data

```bash
# Play the recording locally
asciinema play demo.cast
```

## How it works

1. **Learn mode** — A Java agent instruments application classes to record which ones each test class exercises. The plugin writes a dependency index (`.test-order/test-dependencies.lz4`) directly during the run. (`.deps` files are a fallback path and can still be aggregated manually.)
2. **Order mode** — A JUnit `ClassOrderer` (or TestNG `IMethodInterceptor`) reads the dependency index and a set of changed classes, then sorts test classes so those with the highest overlap run first.

### Framework support

| Framework | Learn mode | Order mode | Auto-discovery |
|-----------|-----------|-----------|----------------|
| **JUnit 5 / 6** | `TelemetryListener` (JUnit Platform) | `PriorityClassOrderer` + `PriorityMethodOrderer` | Via JUnit service files |
| **TestNG 7.x+** | `TestNGTelemetryListener` (`ITestListener`) | `TestNGPriorityInterceptor` (`IMethodInterceptor`) | Via `META-INF/services/org.testng.ITestNGListener` |
| **Kotest** | Via JUnit Platform (Kotest runner) | Via JUnit Platform | Same as JUnit |

Both JUnit and TestNG modules share the same scoring engine (`TestScorer`, `MethodScorer`) from `test-order-core`. No configuration changes are needed — the Maven/Gradle plugins automatically detect which framework is on the test classpath and add the appropriate module.

### Parallel execution

Class-level test parallelism is supported when not in learn mode. The `PriorityClassOrderer` determines the priority order *before* JUnit dispatches execution, so it works correctly with:

- Surefire `<parallel>classesAndMethods</parallel>` or `<parallel>all</parallel>`
- JUnit `junit.jupiter.execution.parallel.mode.classes.default=concurrent`
- Multiple forks (`<forkCount>2</forkCount>`)

In parallel mode the computed order becomes a **scheduling priority hint** — higher-priority classes are started first, but multiple classes may execute concurrently. This still gives fast feedback on likely failures while utilising available CPU cores.

> **Note:** In learn mode, class-level parallelism is **rejected** (the build will fail) because concurrent execution corrupts dependency tracking by blurring which test triggered which class load. Use sequential class execution (`<parallel>methods</parallel>` is fine) during learn runs.

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

## Normal workflow

### Common workflows

1. **Bootstrap dependency index**

  ```bash
  mvn test -Dtestorder.mode=learn
  ```

2. **Default local loop (combined auto mode)**

  ```bash
  mvn test-order:auto test
  ```

  In auto mode, combined runs a full learn pass every
  `-Dtestorder.autoLearnRunThreshold=<N>` runs (default: `10`).

3. **Fast local fail-fast loop**

  ```bash
  mvn test-order:auto test \
    -Dtestorder.changeMode=uncommitted \
    -Dtestorder.select.topN=5 \
    -Dtestorder.select.randomM=0
  ```

4. **Two-phase CI workflow**

  ```bash
  mvn test-order:select test
  mvn test-order:run-remaining test
  ```

5. **Inspect prioritization without executing tests**

  ```bash
  mvn test-order:show-order
  mvn test-order:dump
  ```

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

### Automatic dependency change detection

The plugin fingerprints the **resolved test classpath** (JAR names, sizes, and timestamps) on every run. When it detects a change — such as a SNAPSHOT rebuild, a version bump, or a new transitive dependency — it automatically switches to learn mode to rebuild the index:

```
[test-order] Dependency change detected (resolved classpath differs)
  — switching to learn mode to refresh index.
```

This means you don't need to manually re-run learn mode after updating dependencies. The existing index is still used for ordering during the current learn run, so you get the benefit of prioritization even while relearning.

Detected changes include:
- **SNAPSHOT updates** — rebuilt SNAPSHOTs have new timestamps/sizes
- **Version bumps** — e.g. upgrading `spring-boot` from 3.1 to 3.2
- **Added/removed transitive dependencies** — new JARs on the classpath
- **Lock-file changes** — Gradle lock files or version catalogs

The fingerprint is stored in the state file (`.test-order-state`) and only compared against the previous run's classpath — so the first run after adding the plugin simply records the baseline without triggering a learn.

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
java -jar test-order-core-jar-with-dependencies.jar optimize .test-order/state.lz4
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

Default mode is `uncommitted`, which detects staged, unstaged, and untracked file changes in your working tree.
You can override this with `-Dtestorder.changeMode=<mode>` or configure `auto` to fall back through:
- `explicit` when `testorder.changed.classes` is provided
- `since-last-run` if snapshots exist
- `since-last-commit` otherwise

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

## Annotations

The `test-order-annotations` module provides lightweight annotations that
influence ordering without changing scores. Add the dependency:

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-annotations</artifactId>
  <version>${testorder.version}</version>
  <scope>test</scope>
</dependency>
```

### `@AlwaysRun`

Marks a test class or method so it is **always included** and **pinned to run
first**, regardless of score-based ordering.

```java
import me.bechberger.testorder.AlwaysRun;

@AlwaysRun
public class SmokeTest { … }
```

Behaviour:

- **Order / combined mode:** `@AlwaysRun` classes (and methods) are moved to
  the front of the execution order, before all score-ordered tests. Among
  multiple `@AlwaysRun` classes, alphabetical order is used.
- **Select mode:** `@AlwaysRun` classes are unconditionally included in the
  selected subset (they count toward the budget but are never dropped).
- **`@AlwaysRun` on methods:** Within a single test class, annotated methods
  are pinned first, before score-ordered methods.
- **Precedence:** `@AlwaysRun` takes precedence over `@TestOrder(priority = LAST)`.
  A class or method annotated with both will still be pinned first.

### JUnit `@Order` compatibility

If a test class uses JUnit's own ordering annotations, test-order leaves it
alone:

- **Class-level:** Classes annotated with `@Order` are placed in their own
  group sorted by `@Order` value (ascending), inserted after `@AlwaysRun`
  classes but before score-ordered classes.
- **Method-level:** If any method in a class carries `@Order`, or the class
  is annotated with `@TestMethodOrder`, test-order skips method reordering
  entirely for that class, letting JUnit's built-in ordering take effect.

```java
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StepByStepTest {
    @Test @Order(1) void login() { … }
    @Test @Order(2) void browse() { … }
    @Test @Order(3) void checkout() { … }
}
```

test-order will not reorder the methods inside `StepByStepTest`.

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
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=METHOD_ENTRY
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL_METHOD
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=FULL_MEMBER
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

### Select mode (two-phase CI workflow)

Split your test suite into two Maven invocations for fast feedback:

```bash
# Phase 1 — run the critical subset (fail-fast)
mvn test-order:select test

# Phase 2 — if phase 1 passed, run everything else
mvn test-order:run-remaining test
```

**Phase 1 (`select`)** picks tests in four priority tiers:

1. **`@AlwaysRun` tests** — classes (or methods) annotated with `@AlwaysRun` are unconditionally included and pinned first. Use this for smoke tests or critical-path tests that must never be skipped.
2. **New tests** — test classes that do not appear in the dependency index yet (added since the last learn run).
3. **Affected tests** — all tests whose dependency set overlaps with changed source classes, ranked by the scoring formula. By default every affected test is included (`topN=-1`). Set `topN` to a positive number to cap the selection.
4. **Diverse fast tests** — `M` additional fast tests chosen greedily by Jaccard distance to maximise code-coverage breadth.

Selected test FQCNs are written to `target/test-order-selected.txt` (one per line) and Surefire is configured to run only those classes. All other test classes are written to `target/test-order-remaining.txt`.

**Phase 2 (`run-remaining`)** reads `target/test-order-remaining.txt` and configures Surefire to run exactly those deferred classes. If the file is missing or empty, tests are skipped.

#### Example: automatic change detection (default)

```bash
# Phase 1: plugin auto-detects changed files via git
mvn test-order:select test

# Phase 2: run whatever was deferred
mvn test-order:run-remaining test
```

To override change detection for scripting or debugging, use explicit mode:

```bash
mvn test-order:select test \
    -Dtestorder.changeMode=explicit \
    -Dtestorder.changed.classes=com.myapp.MathService
```

#### Adding `@AlwaysRun` smoke tests

```java
import me.bechberger.testorder.AlwaysRun;

@AlwaysRun
class SmokeTest {
    @Test void healthCheck() { /* ... */ }
}
```

`SmokeTest` will appear in phase 1 regardless of what code changed.

```bash
mvn test -Pselect                # phase 1
mvn test -Prun-remaining         # phase 2
```

#### Selection parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `topN` | `testorder.select.topN` | `-1` | Number of top-scored affected tests to include (`-1` = all affected) |
| `randomM` | `testorder.select.randomM` | `10` | Number of random fast tests for coverage diversity |
| `seed` | `testorder.select.seed` | — | Random seed for reproducible selection |
| `remainingFile` | `testorder.select.remainingFile` | `target/test-order-remaining.txt` | File for deferred test classes |
| `selectedFile` | `testorder.select.selectedFile` | `target/test-order-selected.txt` | File for selected test classes |

### Auto mode (combined goal)

A single goal that handles the full workflow automatically:

1. If no dependency index exists → runs in learn mode (agent attached, all tests)
2. If `runsSinceLearn >= testorder.autoLearnRunThreshold` (default `10`) → runs full learn mode
3. Otherwise → selects a fast subset and configures Surefire to run it first
4. If that selected subset passes, run deferred tests in a second step (`mvn test-order:run-remaining test`)
5. Periodically triggers weight optimisation (every N successful runs)

```bash
mvn test-order:auto test
```

| Parameter | Property | Default | Description |
|---|---|---|---|
| `runRemaining` | `testorder.combined.runRemaining` | `true` | Automatically run remaining tests after the selected subset (`false` = fail-fast subset only) |
| `optimizeEvery` | `testorder.combined.optimizeEvery` | `10` | Optimise weights every N runs (0 = never) |
| `autoLearnRunThreshold` | `testorder.autoLearnRunThreshold` | `10` | In auto mode, force a full learn pass every N runs (0 = disable periodic auto-learn) |

Use auto mode via the `combined` goal when you want one command for the
selected fail-fast phase:

```bash
mvn test-order:auto test
```

Then run the deferred tests only when the first command succeeds:

```bash
mvn test-order:auto test && mvn test-order:run-remaining test
```

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

  # Every PR: two-phase workflow
  #   Phase 1 runs @AlwaysRun + new + affected + diverse fast tests
  #   Phase 2 runs the remaining tests (only if phase 1 passed)
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

### Additional goals

| Goal | Description |
|---|---|
| `test-order:learn` | Force learn mode (attach agent, execute full test run, rebuild dependency index) |
| `test-order:export-json` | Export dependency index as JSON (stdout by default, or `-Dtestorder.exportJson.output=...`) |
| `test-order:snapshot` | Save source/test file hash snapshots for `since-last-run` change detection |
| `test-order:help` | Display all plugin goals and common configuration properties |

### Skipping the plugin

To disable test-order entirely for a specific invocation:

```bash
mvn test -Dtestorder.skip=true
```

Or set `<skip>true</skip>` in the plugin `<configuration>` block.

### Plugin parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `skip` | `testorder.skip` | `false` | Skip the plugin entirely |
| `mode` | `testorder.mode` | `auto` | `auto`, `learn`, `order`, or `skip` |
| `indexFile` | `testorder.index` | `${project.basedir}/.test-order/test-dependencies.lz4` | Dependency index path |
| `depsDir` | `testorder.depsDir` | `${project.build.directory}/test-order-deps` | Directory for `.deps` files |
| `includePackages` | `testorder.includePackages` | — | Additional comma-separated package prefixes to instrument (merged with auto-detected source packages) |
| `filterByGroupId` | `testorder.filterByGroupId` | `true` | Fall back to groupId when no source packages are detected |
| `instrumentationMode` | `testorder.instrumentation.mode` | `FULL` | `FULL`, `METHOD_ENTRY`, `FULL_METHOD`, or `FULL_MEMBER` |
| `changeMode` | `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
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

## Coverage analysis

The `test-order-coverage-mojo` module analyses JaCoCo coverage reports across all modules in a multi-module build, identifies least-tested classes, and generates markdown + JSON reports:

```bash
mvn test-order:coverage
```

Parameters:

| Parameter | Property | Default | Description |
|---|---|---|---|
| `threshold` | `coverage.threshold` | `50` | Coverage threshold percentage |
| `outputDir` | `coverage.outputDir` | `target/coverage-reports` | Report output directory |
| `outputFormat` | `coverage.outputFormat` | `comprehensive` | `comprehensive` (markdown + JSON), `markdown`, or `json` |
| `includeModules` | `coverage.includeModules` | — | Comma-separated module filter |

Requires JaCoCo coverage reports to be present (run `mvn verify` with JaCoCo first).

## CI artifact downloading

On feature branches you can skip the cold-start learn phase by downloading the dependency index from your CI system (GitHub Actions, GitLab CI, or a generic HTTP endpoint). Both the Maven and Gradle plugins integrate this automatically when a `.test-order/download-config.yml` file is present.

See the [test-order-ci README](test-order-ci/README.md) for configuration, usage, and a sample GitHub Actions workflow.

## Structural change analysis

Beyond simple file-level change detection, test-order performs **structural diff analysis** that parses Java sources at the method/field level. This means renaming a method or changing a field is detected more precisely than a line-level diff.

Two parser backends are available:

| Backend | Property value | Description |
|---|---|---|
| **Island** (default) | `island` | Fast regex-based parser, no extra dependencies |
| **JavaParser** | `javaparser` | Full AST-based parser, requires `com.github.javaparser:javaparser-core` on classpath |

Structural diff is enabled by default. To disable:

```bash
mvn test -Dtestorder.structuralDiff.enabled=false
```

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
# → default: target/test-order-dashboard/index.html

# Serve the configured dashboard output over local HTTP
mvn test-order:serve
# → opens http://localhost:<port> automatically
```

Common options:

- `-Dtestorder.dashboard.output=...` to change output file path
- `-Dtestorder.dashboard.port=8080` to set serving port
- `-Dtestorder.dashboard.regenerate=true` to regenerate before serving

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
- **`MavenRunner`** — runs Maven goals (`learn()`, `order()`, `auto()`, `showOrder()`, `dump()`, `select()`, `exportJson()`, etc.) capturing output and exit code.
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

## Compatibility & Coexistence

### Agent coexistence (JaCoCo, Mockito, etc.)

test-order injects a `-javaagent` flag into Surefire's `argLine`. If your POM already defines a custom `<argLine>` (e.g. for JaCoCo or Mockito inline), use Maven's late-binding `@{argLine}` syntax to **chain** agents instead of overwriting:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <!-- @{argLine} is replaced at execution time with the agent flags -->
    <argLine>@{argLine} -Xmx1024m</argLine>
  </configuration>
</plugin>
```

If the plugin detects a hardcoded `<argLine>`, it automatically falls back to injecting the agent via the `maven.surefire.debug` property, preserving both agents. However, using `@{argLine}` is still recommended for clean agent chaining.

### JDK 21+ dynamic agent warnings

Starting with JDK 21 ([JEP 451](https://openjdk.org/jeps/451)), the JVM prints a warning when agents are loaded dynamically:

```
WARNING: A Java agent has been loaded dynamically...
```

test-order attaches its agent via the static `-javaagent` flag (not dynamic attach), so this warning typically does **not** appear. If you see it — e.g. because another tool triggers dynamic loading — suppress it with:

```bash
mvn test -DargLine="-XX:+EnableDynamicAgentLoading"
```

### Parallel Maven builds (`-T`)

test-order supports parallel module execution (`mvn ... -T 1C`). The dependency index and state files use file-level locking and atomic temp-file replacement to prevent corruption when multiple forks write concurrently.

## Troubleshooting

### Quick diagnosis table

| Symptom | Probable cause | Resolution |
|---|---|---|
| Tests execute in default order; index exists | Framework override | Ensure no `@TestMethodOrder` annotations are forcing JUnit to bypass `PriorityClassOrderer`. Run `mvn test-order:show-order -Dtestorder.debug=true` to inspect. |
| JaCoCo reports 0% coverage after adding test-order | `argLine` overwrite collision | The plugin auto-detects hardcoded `<argLine>` and falls back to `maven.surefire.debug`. If you still see issues, use `@{argLine}` syntax. See [Agent coexistence](#agent-coexistence-jacoco-mockito-etc). |
| `NegativeArraySizeException` during parallel build | Stale index from interrupted write | Delete `.test-order/` and re-run `mvn test -Dtestorder.mode=learn`. |
| Stale ordering after refactor | Index references old class names | Re-run learn mode: `mvn test -Dtestorder.mode=learn` |
| JVM warning about dynamic agent loading | JDK 21+ JEP 451 | Pass `-DargLine="-XX:+EnableDynamicAgentLoading"` or verify the warning comes from another tool, not test-order. |
| Empty index despite `src/main/java` existing | Non-standard package layout | Set `-Dtestorder.includePackages=com.yourcompany` explicitly and run with `-Dtestorder.debug=true`. |

### Detailed troubleshooting

For issues not covered above:

- Run with `-Dtestorder.debug=true` to surface change-detection details and scoring decisions.
- Switch to `METHOD_ENTRY` instrumentation mode to isolate bytecode transformation issues.
- Compare a plain `mvn test` run against `mvn test -Dtestorder.mode=learn` to identify agent conflicts.
- If `.test-order/state.lz4` is corrupted, delete the entire `.test-order/` directory and rebuild:

```bash
rm -rf .test-order
mvn test -Dtestorder.mode=learn
```

## FAQ

### Does it support both JUnit 5 and JUnit 6?

Yes. The project supports Jupiter on the JUnit Platform for both **JUnit 5.x** and **JUnit 6.x**, and the repository keeps fixture coverage for both lines instead of collapsing onto a single version.

### Does it work with JaCoCo?

Usually yes. JaCoCo and `test-order` can coexist because both are standard Java agents, but agent ordering and other bytecode transformers can still matter in a real build. Validate the final combination in your project, especially if you also use Mockito inline or other instrumentation-heavy tooling.

### What about parameterized tests, Spring test slices, or Kotest?

`test-order` prioritizes **test classes** first and optionally prioritizes **test methods** when method-level telemetry is enabled.

- **Parameterized tests**: Work on JUnit Platform; class-level ordering works normally. When method-level ordering is enabled, all invocations of a parameterized method are grouped together — individual parameter invocations cannot be reordered (JUnit Platform limitation).
- **Spring test slices**: Treated as regular test classes; dependency data is aggregated normally.
- **Kotlin/Kotest**: **Fully supported** when using the JUnit Platform runner. See [Kotlin & Kotest Support](#kotlin--kotest-support) below for details and limitations.

### Kotlin & Kotest Support

`test-order` has **limited but tested** support for Kotlin projects using Kotest with the JUnit Platform runner.

**What works:**
- ✅ Kotest `StringSpec`, `FunSpec`, and other spec styles via JUnit Platform runner (kotest-runner-junit5)
- ✅ Kotlin source detection in `src/main/kotlin` and `src/test/kotlin`
- ✅ Dependency tracking on compiled Kotlin bytecode (no language-specific handling needed)
- ✅ Change detection on `.kt` source files
- ✅ Learn mode builds dependency indices for Kotest tests
- ✅ Class-level test ordering applied to Kotest test specs

**Limitations:**
- 🔶 **Method-level ordering**: Kotest's DSL-based test definitions map to a single test spec class; method-level ordering may not align with test case structure.
- 🔶 **Inline functions**: Kotlin `inline fun` calls are erased by the compiler (bytecode is copied into the call site). In `FULL_MEMBER` mode, the agent cannot track the inlined call, so dependency precision is reduced. Use `FULL` mode (the default) for Kotlin projects — it tracks at class level and is not affected by inlining.
- 🔶 **Tested with**: Kotest 5.9.1 + JUnit Platform runner. Other Kotest configurations may behave differently.
- 🔶 **Not supported**: Kotest tests run directly (without JUnit Platform runner) will not be reordered.

**Maven Example:**
```xml
<dependency>
    <groupId>io.kotest</groupId>
    <artifactId>kotest-runner-junit5-jvm</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.kotest</groupId>
    <artifactId>kotest-assertions-core-jvm</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
```

Verify Kotest integration with:
```bash
mvn clean test-order:auto test
```

See [test-order-example-kotlin](test-order-example-kotlin) and [fixture-kotest](test-order-junit/test-fixtures/fixture-kotest) for working examples.

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

### Available Gradle tasks

| Task | Description |
|---|---|
| `testOrderAggregate` | Aggregate `.deps` files into dependency index |
| `testOrderDump` | Dump dependency index contents |
| `testOrderShowOrder` | Show current priority order |
| `testOrderOptimize` | Optimise scoring weights |
| `testOrderSelect` | Select fast CI subset |
| `testOrderRunRemaining` | Run deferred tests |
| `testOrderClean` | Clean all test-order state, indexes, and hashes |
| `testOrderDashboard` | Generate interactive HTML dashboard |
| `testOrderServe` | Serve dashboard via local HTTP server |

## Advanced configuration

Beyond the standard plugin parameters, these system properties control advanced behaviour:

### Auto-learn

| Property | Default | Description |
|---|---|---|
| `testorder.autoLearnRunThreshold` | `0` (disabled) | Automatically re-learn after N order-mode runs |
| `testorder.autoLearnDiffThreshold` | `0` (disabled) | Automatically re-learn when changed file count ≥ threshold |

### Additional scoring properties

| Property | Default | Description |
|---|---|---|
| `testorder.score.coverageBonus` | `0` (disabled) | Set-cover algorithm bonus for coverage diversity |
| `testorder.score.springContextGrouping` | — | Bonus for Spring-annotated tests sharing context |
| `testorder.score.ema.varianceThreshold` | — | EMA variance threshold for duration stability |

### Method-level scoring overrides

When method-level ordering is enabled (`testorder.methodOrder=true`), individual method scoring components can be tuned:

| Property | Description |
|---|---|
| `testorder.method.score.failureRecency` | Method failure recency weight |
| `testorder.method.score.newMethod` | New method bonus |
| `testorder.method.score.changedMethod` | Changed method bonus |
| `testorder.method.score.fast` | Fast method bonus |
| `testorder.method.score.slow` | Slow method penalty |
| `testorder.method.score.depOverlap` | Per-method dependency overlap |
| `testorder.method.score.coverageBonus` | Per-method coverage bonus |

### Runtime properties

| Property | Description |
|---|---|
| `testorder.debug` | Enable verbose debug output for ordering and change detection |
| `testorder.project.root` | Git project root for change detection |
| `testorder.source.root` | Custom source root (overrides auto-detected `src/main/java`) |
| `testorder.history.maxRuns` | Maximum run history entries (default: 50) |
| `testorder.structuralDiff.enabled` | Enable/disable structural change analysis (default: true) |
| `testorder.changed.classes.file` | Read changed classes from a file |
| `testorder.changed.methods` | Explicit changed methods list |

## Further documentation

The [docs/](docs/) directory contains in-depth guides:

| Document | Description |
|---|---|
| [CLI_REFERENCE.md](docs/CLI_REFERENCE.md) | Complete CLI reference, properties, change-detection modes, dashboard and CI patterns |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, data flow, and extension/contribution guidance |
| [PERFORMANCE_TUNING.md](docs/PERFORMANCE_TUNING.md) | Measurement-first performance tuning guide |

## Project structure

```
test-order-agent/             Java agent (bytecode instrumentation via javassist)
test-order-core/              Core engine: dependency index, scoring, change detection, CLI tool
test-order-junit/             JUnit extension and listeners/orderers
test-order-maven-plugin/      Maven plugin (prepare, aggregate, snapshot goals)
test-order-gradle-plugin/     Gradle plugin (learn, order, utility tasks)
test-order-ci/                CI artifact downloader (GitHub Actions, GitLab CI, HTTP support)
test-order-coverage-mojo/     Coverage analysis and least-tested class detection
test-order-example/           Minimal Maven example project
test-order-example/test-order-example-gradle/  Minimal Gradle example project
test-order-example/test-order-example-junit5/  Maven compatibility fixture for JUnit 5
test-order-example/test-order-example-kotlin/  Kotlin Maven example
test-order-example/test-order-example-service/ Larger service-style Maven example
test-order-example/test-order-fields-methods-example/ Field/method scoring example
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

## Contributing

### Quality profiles

The build includes two code quality profiles for static analysis:

```bash
# Checkstyle, SpotBugs, Spotless
mvn clean verify -Pquality

# Google Error Prone static analysis
mvn clean verify -Pquality-errorprone

# Both together
mvn clean verify -Pquality -Pquality-errorprone
```

### Dashboard UI tests

The `test-order-dashboard-ui-tests` module contains Playwright-based E2E tests for the HTML dashboard.
These tests are **skipped by default** and must be explicitly enabled:

```bash
# Install browser (one-time)
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium" -pl test-order-dashboard-ui-tests

# Run UI tests (requires -Dtestorder.ui=true)
mvn verify -pl test-order-dashboard-ui-tests -Dtestorder.ui=true
```

## How test-order compares to other tools

The Java ecosystem offers several approaches to reducing test execution time. test-order focuses on **zero-infrastructure, deterministic prioritization** — here is how it compares:

### Dynamic Test Impact Analysis (TIA)

| Tool | Category | Approach | Key trade-off vs test-order |
|---|---|---|---|
| [Skippy](https://www.skippy.io/) | Coverage TIA | JaCoCo per-test coverage | Requires `@PredictWithSkippy` on every test class |
| [OpenClover](https://openclover.org/) | Coverage TIA | Compile-time source instrumentation | Modifies sources during build; risks artifact pollution |
| [Ekstazi](http://ekstazi.org/) | Dynamic RTS | File-level dependency tracking | File-level only; needs JVM flags on Java 11+ |
| [STARTS](https://github.com/TestingResearchIllinois/starts) | Static RTS | Dependency graph via `jdeps` | Over-selects; blind to Reflection / Spring DI |
| [Develocity](https://gradle.com/develocity/) | ML / PTS | Cloud ML on Build Scan history | Probabilistic (can skip relevant tests); expensive SaaS |
| [Launchable](https://www.launchable.com/) | ML / PTS | Cloud ML on test history + Git metadata | Same probabilistic risk; data sent to third-party cloud |
| [Drill4J](https://drill4j.github.io/) | Enterprise TIA | Distributed admin server + agents | Requires deploying standalone servers and databases |
| [SeaLights](https://www.sealights.io/) | Enterprise TIA | Commercial SaaS quality governance | Closed-source; high cost; data leaves your infrastructure |

**Why test-order?** It combines dynamic runtime accuracy (like Ekstazi/Skippy) with intelligent prioritization and local adaptive tuning (like Develocity) — no annotations, no cloud, no infrastructure. Data stays in your Git repo.

## License

[MIT](LICENSE)



- find usuabiltiy issues, unexpected/surprising behaviour and more by using the plugin, like a normal user would, you have a lot of example projects in this main, record the found bugs with reproducer steps (integration test or manual steps) and fix them, then re-run the tests to verify the fix and prevent regressions


TODO:
- find usability issues, unexpected/surprising behaviour and more by using the plugin




