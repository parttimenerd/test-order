# test-order

[![CI](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml)

Large test suites destroy developer momentum. A suite that once ran in minutes can bloat into an hour-long bottleneck, burying relevant failures somewhere in the middle of the output.

<!-- Add to pom.xml → run `mvn test` → affected tests run first. That's it. -->
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <executions><execution><goals><goal>prepare</goal></goals></execution></executions>
</plugin>
```

**test-order** is a zero-configuration Maven and Gradle plugin that uses runtime dependency telemetry to identify which tests exercise your modified code — then forces them to the **front of the execution queue**. Typical projects see relevant failures surface within the first 5–10% of the suite.

Works with **JUnit 5** (Jupiter 5.x), **JUnit 6** (Jupiter 6.x), and **TestNG** (7.x+) on Java 17+. Kotlin projects using Kotest with the JUnit Platform runner are also supported; see [docs/KOTEST.md](docs/KOTEST.md) for details.

### Guides

| | |
|---|---|
| **[Getting Started Tutorial](docs/GETTING_STARTED.md)** | Step-by-step walkthrough: first run → reordering → dashboard |
| **[CI Setup](docs/ci-examples/)** | Ready-to-use GitHub Actions, GitLab CI, Azure Pipelines configs |
| **[Multi-Module Setup](docs/MULTI_MODULE_SETUP.md)** | Configuring reactor-level aggregation |
| **[Building from Source](docs/DEVELOPMENT.md)** | Compile and use the development version locally |
| **[Samples](samples/README.md)** | Hands-on example projects to experiment with |
| **[Full Documentation](docs/README.md)** | Architecture, scoring, CLI reference, and more |

## Quick Start

For most projects, zero configuration is needed — just add the plugin and run your tests.

### Maven

Add the plugin to your `pom.xml` (as shown above) and run:

```bash
# First run: learns test dependencies automatically
mvn test

# Subsequent runs: reorders tests by priority (affected tests first)
mvn test

# Skip the plugin entirely for a vanilla run
mvn test -Dtestorder.skip=true
```

> **Tip:** The plugin is adaptive — it auto-switches to learn mode when it detects classpath changes or after every 10 order-mode runs (configurable via `testorder.autoLearnRunThreshold`). This keeps the index fresh without manual intervention.

> **Alternative (no POM changes):** Invoke `mvn test-order:auto test` directly without any `<execution>` block.

<a id="gradle"></a>

### Gradle

Add the plugin to your `build.gradle` (Groovy DSL):

```groovy
// settings.gradle — required until the plugin is published to the Gradle Plugin Portal
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

// build.gradle
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT'
}
```

Or `build.gradle.kts` (Kotlin DSL):

```kotlin
plugins {
    id("me.bechberger.test-order") version "0.0.1-SNAPSHOT"
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

That's it! Most single-module projects need no further configuration.

**For advanced configuration and complete CLI reference**: See [docs/CLI_REFERENCE.md](docs/CLI_REFERENCE.md)

### Maven vs Gradle at a glance

| Feature | Maven | Gradle |
|---|---|---|
| Plugin application | `<plugin>` in `pom.xml` | `plugins { id 'me.bechberger.test-order' }` |
| Auto mode | `mvn test-order:auto test` | `./gradlew test` (auto-detected) |
| Learn mode | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` |
| Show order + ML | `mvn test-order:show` | `./gradlew testOrderShow` |
| Dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Detect OD tests | `mvn test-order:detect-dependencies` | `./gradlew testOrderDetectDependencies` |
| Export index JSON | `mvn test-order:export-json` | `./gradlew testOrderExportJson` |
| Diagnose setup | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| Optimise weights | `mvn test-order:optimize` | `./gradlew testOrderOptimize` |
| Select subset | `mvn test-order:select test` | `./gradlew testOrderSelect` |

## Fail your tests fast

[![asciicast](https://asciinema.org/a/QhXjJtvug2nR2VVh.svg)](https://asciinema.org/a/QhXjJtvug2nR2VVh)

> *Fail your tests fast by using an intelligent test order.*

<details>
<summary><strong>What the demo shows (Spring Petclinic walkthrough)</strong></summary>

The recording demonstrates the full workflow on **Spring Petclinic** (~24 test classes):

1. **Learn** — `mvn test` collects which test class exercises which source class.
2. **Modify source** — Touch `OwnerController.java`; `OwnerControllerTests` jumps to rank 1.
3. **Run prioritized** — Affected tests run first, giving instant feedback.
4. **Second change** — Touch `VetController`; the order shifts automatically.
5. **Dashboard** — `mvn test-order:dashboard` generates an interactive HTML report.

```bash
asciinema play demo.cast
```

</details>

## How it works

1. **Learn mode** — A Java agent instruments application classes to record which ones each test class exercises. The plugin writes a dependency index (`.test-order/test-dependencies.lz4`) directly during the run.
2. **Order mode** — A JUnit `ClassOrderer` (or TestNG `IMethodInterceptor`) reads the dependency index and a set of changed classes, then sorts test classes so those with the highest overlap run first.

### Change detection

The plugin needs to know *what changed* to score tests. Change detection mode is configurable via `-Dtestorder.changeMode=<mode>`:

| Mode | Behaviour | Best for |
|------|-----------|----------|
| `uncommitted` (default) | Detects staged, unstaged, and untracked changes in your working tree | Local development |
| `auto` | Uses `since-last-run` if hash snapshot exists, otherwise `since-last-commit` | Most projects |
| `since-last-commit` | Compares working tree against `HEAD` via `git diff` | CI / branch validation |
| `since-last-run` | Compares file hashes against the previous test run's snapshot | CI without git history / shallow clones |
| `explicit` | Only scores classes listed in `-Dtestorder.changed.classes=...` | Scripted pipelines |

### Framework support

| Framework | Learn mode | Order mode | Auto-discovery |
|-----------|-----------|-----------|----------------|
| **JUnit 5 / 6** | `TelemetryListener` (JUnit Platform) | `PriorityClassOrderer` + `PriorityMethodOrderer` | Via JUnit service files |
| **TestNG 7.x+** | `TestNGTelemetryListener` (`ITestListener`) | `TestNGPriorityInterceptor` (`IMethodInterceptor`) | Via `META-INF/services/org.testng.ITestNGListener` |
| **Kotest** | Via JUnit Platform (Kotest runner) | Via JUnit Platform | Same as JUnit |

Both JUnit and TestNG modules share the same scoring engine (`TestScorer`, `MethodScorer`) from `test-order-core`. No configuration changes are needed — the Maven/Gradle plugins automatically detect which framework is on the test classpath and add the appropriate module.

**Parameterized tests** work normally at class level. With method-level ordering, all invocations of a parameterized method are grouped together (JUnit Platform limitation). **Spring test slices** are treated as regular test classes. **Kotest** is fully supported via the JUnit Platform runner — see [docs/KOTEST.md](docs/KOTEST.md).

### Parallel execution

Class-level test parallelism is supported when not in learn mode. The `PriorityClassOrderer` determines the priority order *before* JUnit dispatches execution, so it works correctly with:

- Surefire `<parallel>classesAndMethods</parallel>` or `<parallel>all</parallel>`
- JUnit `junit.jupiter.execution.parallel.mode.classes.default=concurrent`
- Multiple forks (`<forkCount>2</forkCount>`)

In parallel mode the computed order becomes a **scheduling priority hint** — higher-priority classes are started first, but multiple classes may execute concurrently. This still gives fast feedback on likely failures while utilising available CPU cores.

> **Note:** In learn mode, class-level parallelism is **rejected** (the build will fail) because concurrent execution corrupts dependency tracking by blurring which test triggered which class load. Use sequential class execution (`<parallel>methods</parallel>` is fine) during learn runs.

Parallel module execution (`mvn ... -T 1C`) is also supported. The dependency index and state files use file-level locking and atomic temp-file replacement to prevent corruption when multiple forks write concurrently.

<details>
<summary><strong>How test-order compares to other tools</strong></summary>

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

</details>

## Normal workflow

### Common workflows

1. **Bootstrap dependency index** (only needed if `prepare` is not bound)

  ```bash
  mvn test -Dtestorder.mode=learn
  ```

2. **Default local loop** (with `prepare` bound in POM)

  ```bash
  mvn test
  ```

  With `prepare` bound, plain `mvn test` auto-learns on first run and reorders
  on subsequent runs.

3. **Fast local fail-fast loop** (subset of affected tests only)

  ```bash
  mvn test-order:select test \
    -Dtestorder.select.topN=5 \
    -Dtestorder.select.randomM=0
  ```

4. **Two-phase CI workflow**

  ```bash
  mvn test-order:select test
  mvn test-order:run-remaining test
  ```

5. **Three-tier CI workflow** (fastest feedback → broader coverage → full suite)

  ```bash
  # Tier 1: change-affected + @AlwaysRun + new tests (~10-20%)
  mvn test-order:tiered-select test -Dtestorder.tiered.tier2Fraction=0.5

  # Tier 2: top-scored 50% of remaining by duration budget
  mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2

  # Tier 3: everything else
  mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
  ```

  Ready-to-use CI configs for GitHub Actions, GitLab CI, and Azure Pipelines: [docs/ci-examples/](docs/ci-examples/).

  > **Cold start?** Use `mvn test-order:download` to fetch the dependency index from a previous CI run instead of learning from scratch. See [test-order-ci](test-order-ci/README.md).

6. **Inspect prioritization without executing tests**

  ```bash
  mvn test-order:show
  mvn test-order:dump
  ```

### One-time setup

If you bound `prepare` in your POM (see [Quick Start](#quick-start)), **no separate setup is needed** — the first `mvn test` auto-learns and creates the index. Otherwise run `mvn test -Dtestorder.mode=learn` once.

#### What gets generated

| Path | Purpose | Commit? |
|---|---|---|
| `.test-order/test-dependencies.lz4` | Dependency index | **Yes** |
| `.test-order/state.lz4` | Test durations, failure history | Optional |
| `.test-order/ml/history.lz4` | ML run history (when `ml.enabled=true`) | Optional |
| `.test-order/hashes.lz4` | Source hash snapshot (machine-local) | No |
| `.test-order/method-hashes.lz4` | Method-level hash snapshot | No |
| `.test-order/test-hashes.lz4` | Test source hash snapshot | No |
| `target/test-order-dashboard/` | Static dashboard HTML | No (regenerated) |
| `target/test-order-selected.txt` | Selected test classes | No |
| `target/test-order-remaining.txt` | Deferred test classes | No |

Suggested `.gitignore` additions:
```gitignore
target/test-order-dashboard/
target/test-order-selected.txt
target/test-order-remaining.txt
```

### Day-to-day development

Just run `mvn test` — the plugin auto-detects changed files and reorders tests. If no dependency index exists yet, it logs a warning; run learn mode once first.

> **CI tip:** Run `mvn test -Dtestorder.mode=learn` on your main branch periodically and commit `.test-order/test-dependencies.lz4` to keep the index fresh for feature branches.

## CI Caching

Cache `.test-order/` between CI steps to preserve the dependency index, test state, and hash snapshots. Without this cache, the first run on each PR falls back to learn mode (slower).

### What to cache

| Path | Purpose | Cache? |
|---|---|---|
| `.test-order/test-dependencies.lz4` | Dependency index | **Yes** |
| `.test-order/state.lz4` | Durations + failure history (improves scoring) | **Yes** |
| `.test-order/ml/history.lz4` | ML run history | Yes (if `ml.enabled=true`) |
| `.test-order/hashes/*.lz4` | Hash snapshots for `since-last-run` change detection | Yes |
| `**/target/test-order-deps/` | Per-module `.deps` files (Maven only) | Yes |
| `target/test-order-dashboard/` | Dashboard HTML (regenerated) | No |
| `target/test-order-selected.txt` | Transient selection list | No |

### GitHub Actions

<details>
<summary><strong>Maven</strong></summary>

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/src/**/*.java') }}
    restore-keys: |
      test-order-${{ runner.os }}-

# ... run tests ...

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/src/**/*.java') }}
```

</details>

<details>
<summary><strong>Gradle</strong></summary>

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/*.java') }}
    restore-keys: test-order-${{ runner.os }}-

# ... run tests ...

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/*.java') }}
```

> Gradle doesn't use `target/test-order-deps/` — dependency data is written directly to `.test-order/`.

</details>

### GitLab CI

```yaml
cache:
  key: test-order-${CI_COMMIT_REF_SLUG}
  paths:
    - .test-order/
  policy: pull-push
```

### Azure Pipelines

```yaml
- task: Cache@2
  inputs:
    key: 'test-order | "$(Agent.OS)" | **/src/**/*.java'
    path: .test-order/
    restoreKeys: |
      test-order | "$(Agent.OS)"
```

### Key differences: Maven vs Gradle

| Concern | Maven | Gradle |
|---|---|---|
| Extra cache paths | `**/target/test-order-deps/` (per-module `.deps` files) | Not needed — written to `.test-order/` directly |
| Aggregation step | `mvn test-order:aggregate` (optional, merges `.deps`) | `./gradlew testOrderAggregate` |
| Multi-module index | Single shared `.test-order/` at root | Same — single `.test-order/` at root project |
| Cold start fallback | Learns on first run, or use `mvn test-order:download` | Learns on first run, or `./gradlew testOrderDownload` |

### Tips

- Use `restore-keys` (GitHub Actions) or a branch-based key (GitLab) so PRs inherit the cache from `main`.
- Always save the cache even when tests fail (`if: always()`) — failure history improves future scoring.
- For shallow clones (e.g. `fetch-depth: 1`), use `changeMode=since-last-run` instead of `since-last-commit`.
- Full CI examples: [docs/ci-examples/](docs/ci-examples/).

## Scoring system

Each test class receives a score. Tests are sorted by descending score, with faster tests first among ties.

### Score components

<!-- BEGIN WEIGHTS TABLE -->
| Component | Default | Config property | Description |
|---|---|---|---|
| **New test bonus** | 15 | `testorder.score.newTest` | Bonus for new test classes not in the dependency index |
| **Changed test bonus** | 9 | `testorder.score.changedTest` | Bonus for changed test sources |
| **Failure bonus** | 1–5 | `testorder.score.maxFailure` | Cap on failure-based bonus |
| **Speed bonus** | 1 | `testorder.score.speed` | Bonus for fast tests (logarithmic scale: full bonus at 1/8× median, zero at median) |
| **Speed penalty** | 1 | `testorder.score.speedPenalty` | Penalty for slow tests (logarithmic scale: full penalty at 8× median, zero at median) |
| **Dependency overlap** | 5 (max) | `testorder.score.depOverlap` | Max score from dependency overlap (sqrt-normalized: overlap/√totalDeps × weight) |
| **Change complexity** | 2 (max) | `testorder.score.changeComplexity` | Complexity-weighted overlap using Deflate-compressed file size as information-density proxy |
<!-- END WEIGHTS TABLE -->

<details>
<summary><strong>Scoring formula</strong></summary>

<!-- BEGIN WEIGHTS FORMULA -->
```
score = (isNew ? newTestBonus : 0)
      + (isChanged ? changedTestBonus : 0)
      + min(ceil(recencyWeightedFailures), maxFailureBonus)
      + round(speedRatio × speedBonus)         # speedRatio ∈ [-1, 0] for fast tests
      - round(speedRatio × speedPenalty)        # speedRatio ∈ [0, 1] for slow tests
      + min(ceil(|dependencies ∩ changedClasses| / √|dependencies| × depOverlap), depOverlap)
      + min(ceil(Σ complexity(dep) / √|dependencies| × changeComplexity), changeComplexity)

  where speedRatio = clamp(log₂(duration / median) / 3, -1, 1)
```
<!-- END WEIGHTS FORMULA -->

</details>

For full details on failure decay, duration smoothing, customizing weights (TOML format), automatic score tuning, instrumentation modes/benchmarks, and change detection modes, see **[docs/SCORING.md](docs/SCORING.md)**.

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

### [`@AlwaysRun`](test-order-annotations/src/main/java/me/bechberger/testorder/annotations/AlwaysRun.java)

Marks a test class or method so it is **always included** and **pinned to run
first**, regardless of score-based ordering.

```java
import me.bechberger.testorder.annotations.AlwaysRun;

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

For full Maven goal reference, all parameters, and CI YAML examples, see **[docs/MAVEN_PLUGIN.md](docs/MAVEN_PLUGIN.md)**.

## Additional features

| Feature | Command | Details |
|---|---|---|
| **Dashboard** | `mvn test-order:dashboard` | Interactive HTML report with test explorer, analytics, and weight simulator. Gradle: `./gradlew testOrderDashboard` |
| **ML predictions** | `mvn test-order:show -Dtestorder.show.ml=true` | Failure probability and health classification using logistic regression on test history |
| **Detect OD tests** | `mvn test-order:detect-dependencies` | Find order-dependent (flaky) tests by reordering strategies. See [docs/DETECT_DEPENDENCIES.md](docs/DETECT_DEPENDENCIES.md) |
| **Coverage gaps** | `mvn test-order:coverage` | Identifies least-tested production classes. See [docs/MAVEN_PLUGIN.md](docs/MAVEN_PLUGIN.md#coverage-analysis) |
| **CI index download** | `mvn test-order:download` | Download dependency index from CI instead of cold-start learning. See [test-order-ci README](test-order-ci/README.md) |
| **Method-level ordering** | `mvn test -Dtestorder.methodOrder.enabled=true` | Opt-in reordering within each class by failure recency, change status, speed. See [docs/SCORING.md](docs/SCORING.md#method-level-scoring) |
| **Serve dashboard** | `mvn test-order:serve` | Local HTTP server for the dashboard |

### ML failure predictions

When `testorder.ml.enabled=true`, test-order records per-test outcomes after each run into a compressed history file. After 5+ recorded runs, the ML layer provides:

- **P(fail) predictions** — A Tribuo logistic regression model trained on 26 features (change coupling, failure streaks, co-failure patterns, duration trends, EWMA rates) predicts failure probability per test class. Higher-probability tests get prioritized.
- **Health classification** — Statistical analysis labels each test as **HEALTHY**, **DEGRADING** (failure rate increasing), **FLAKY** (inconsistent pass/fail), or **FAILING** (consistently broken).

```bash
# Enable ML collection (add to POM or pass as system property)
mvn test -Dtestorder.ml.enabled=true

# View ML analysis (auto-detected when history exists)
mvn test-order:show

# Dashboard shows ML Health tab + P(fail) column
mvn test-order:dashboard
```

ML data is stored in `.test-order/ml/history.lz4` (LZ4-compressed binary, max 2000 runs). The model trains in-process on each invocation — no external services or cloud dependencies.

## Gradle plugin

See [Quick Start > Gradle](#gradle) above for setup. Full documentation: [test-order-gradle-plugin/README.md](test-order-gradle-plugin/README.md).

For projects where you don't want to modify build files, use an init script:

```bash
./gradlew test --init-script path/to/test-order-init.gradle -Dtestorder.mode=learn
```

All Gradle tasks (`testOrderShow`, `testOrderAggregate`, `testOrderDump`, `testOrderOptimize`, `testOrderSelect`, `testOrderRunRemaining`, `testOrderClean`, `testOrderDashboard`, `testOrderServe`) mirror their Maven counterparts shown in the [comparison table](#maven-vs-gradle-at-a-glance).

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

> **JDK 21+ note:** test-order uses the static `-javaagent` flag, not dynamic attach, so [JEP 451](https://openjdk.org/jeps/451) warnings do not come from this plugin. If another tool triggers the warning, suppress it with `-DargLine="-XX:+EnableDynamicAgentLoading"`.

## Troubleshooting

> **Start here:** run `mvn test-order:diagnose` — it checks index health, agent attachment, and configuration issues in one shot.

### Quick diagnosis table

| Symptom | Probable cause | Resolution |
|---|---|---|
| Tests in default order despite index | Framework override | Remove `@TestMethodOrder` overrides; run with `-Dtestorder.debug=true` |
| JaCoCo reports 0% coverage | `argLine` collision | Use `@{argLine}` syntax. See [Agent coexistence](#agent-coexistence-jacoco-mockito-etc) |
| `NegativeArraySizeException` in parallel build | Stale index | Delete `.test-order/` and re-learn |
| Stale ordering after refactor | Old class names in index | Re-run `mvn test -Dtestorder.mode=learn` |
| Multi-module: modules stuck on `[NEW]` | First-run race | Learn all modules, then `mvn test-order:aggregate` |
| JVM dynamic agent warning | JDK 21+ JEP 451 | Pass `-XX:+EnableDynamicAgentLoading` or ignore (not from test-order) |
| Empty index despite sources existing | Non-standard packages | Set `-Dtestorder.includePackages=com.yourcompany` |

### Detailed troubleshooting

For issues not covered above:

- Run `mvn test-order:diagnose` for an automated health check of your setup.
- Run with `-Dtestorder.debug=true` to surface change-detection details and scoring decisions.
- Switch to `METHOD_ENTRY` instrumentation mode to isolate bytecode transformation issues.
- Compare a plain `mvn test` run against `mvn test -Dtestorder.mode=learn` to identify agent conflicts.
- If `.test-order/state.lz4` is corrupted, delete the entire `.test-order/` directory and rebuild:

```bash
rm -rf .test-order
mvn test -Dtestorder.mode=learn
```

## Advanced configuration & further documentation

The [docs/](docs/) directory contains in-depth guides:

| Document | Description |
|---|---|
| [MAVEN_PLUGIN.md](docs/MAVEN_PLUGIN.md) | Full Maven plugin reference: all goals, parameters, CI YAML examples, advanced configuration |
| [SCORING.md](docs/SCORING.md) | Scoring internals: formula details, failure decay, duration smoothing, instrumentation modes/benchmarks, weight customization |
| [CLI_REFERENCE.md](docs/CLI_REFERENCE.md) | Complete CLI reference, properties, change-detection modes, dashboard and CI patterns |
| [KOTEST.md](docs/KOTEST.md) | Kotlin & Kotest support: setup, limitations, examples |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, data flow, and extension/contribution guidance |
| [PERFORMANCE_TUNING.md](docs/PERFORMANCE_TUNING.md) | Measurement-first performance tuning guide |

## Development version

To build and use the latest unreleased code:

```bash
git clone https://github.com/parttimenerd/test-order.git
cd test-order && mvn install -DskipTests -Dspotless.check.skip=true
```

This installs the `0.1.0-SNAPSHOT` to your local Maven repository. Use the plugin snippet from [Quick Start](#quick-start) as-is — Maven resolves SNAPSHOTs from `~/.m2` automatically.

Full instructions (Gradle, samples, iteration workflow): **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for PR guidelines, code style, and release process. For building from source, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Support & Feedback

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub issues](https://github.com/parttimenerd/test-order/issues).
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors


