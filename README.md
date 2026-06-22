# test-order

[![CI](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/test-order/actions/workflows/ci.yml)

**Run the tests that matter first.** Add one plugin, run `mvn test` — tests that exercise your changed code move to the front of the queue. No configuration, no cloud, no annotations. Typical projects see relevant failures surface within the **first 5–10%** of the suite.

---

> **New here?** Jump to the [Quick Start](#quick-start) below, or follow the [Getting Started Tutorial](docs/GETTING_STARTED.md) for a hands-on 5-minute walkthrough. Something not working? Run `mvn test-order:diagnose` or see [Troubleshooting](#troubleshooting).

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 17+ |
| Build tool | Maven 3.6+ or Gradle 7.6+ |
| VCS | Git (for change detection) |
| Test framework | JUnit 5/6, JUnit 4 (via Vintage engine), TestNG 7.x+, or Kotest (JUnit Platform) |

<details>
<summary><strong>Check prerequisites</strong></summary>

```bash
java -version   # Must be 17+
mvn --version   # Must be 3.6+
git --version   # Any recent version
```

</details>

## Quick Start

> **ℹ️ Invoking goals from the CLI.** The README uses the short prefix form `mvn test-order:<goal>` (e.g., `mvn test-order:affected`) throughout. There are three ways to run these goals; pick whichever fits your setup.
>
> **Option A — Use the fully-qualified form (no setup).** Always works:
>
> ```bash
> mvn me.bechberger:test-order-maven-plugin:affected
> mvn me.bechberger:test-order-maven-plugin:show
> ```
>
> **Option B — Enable the short prefix (one-time, per developer).** Add `me.bechberger` to your `~/.m2/settings.xml` so Maven recognises `test-order:` as a goal prefix:
>
> ```xml
> <settings>
>   <pluginGroups>
>     <pluginGroup>me.bechberger</pluginGroup>
>   </pluginGroups>
> </settings>
> ```
>
> After this, every short-form example in the README works as written.
>
> **Option C — Bind to a lifecycle phase.** Goals bound via `<execution>` in your `pom.xml` (e.g., `auto` running on `test`) trigger automatically with `mvn test` and need neither setting nor prefix.
>
> **Why is the settings.xml step needed at all?** Maven only resolves goal prefixes for plugins under groupIds it has been told to trust — by default just `org.apache.maven.plugins` and `org.codehaus.mojo`. Declaring the plugin in `<build><plugins>` is **not** sufficient for CLI prefix resolution; it only wires the plugin into the build lifecycle. Without Option A or Option B, ad-hoc CLI calls fail with `No plugin found for prefix 'test-order'`. This is a global Maven design choice, not something a third-party plugin can change.

### Maven

**1. Add the Sonatype snapshot repository** to your `pom.xml` (needed while on `0.0.1-SNAPSHOT`):

```xml
<repositories>
  <repository>
    <id>ossrh-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>ossrh-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <snapshots><enabled>true</enabled></snapshots>
  </pluginRepository>
</pluginRepositories>
```

**2. Add the plugin** to your `pom.xml` inside `<build><plugins>`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <extensions>true</extensions>  <!-- required: registers the lifecycle participant that writes the index -->
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>
```

**3. Run your tests twice:**

```bash
mvn test          # First run: learns which tests cover which code
mvn test          # Second run: reorders — affected tests run first
```

That's it. The plugin auto-switches between learn and order mode. No configuration needed.

**4. Explore (optional):**

```bash
mvn test-order:show           # See how tests are ranked and why
mvn test-order:dashboard      # Interactive HTML report
mvn test-order:diagnose       # Check setup health
mvn test-order:help           # List all goals and options
mvn test -Dtestorder.skip=true  # Skip the plugin entirely
```

> **Want to try without modifying your POM?** Run directly with the fully-qualified goal:
> ```bash
> mvn me.bechberger:test-order-maven-plugin:0.0.1-SNAPSHOT:auto test
> ```
> Or add the `<execution>` block above and use the short prefix `test-order:auto test`.

<a id="gradle"></a>

### Gradle

**1. Add the plugin:**

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven {
            url 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        gradlePluginPortal()
    }
}

// build.gradle
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT'
}
```

<details>
<summary>Kotlin DSL</summary>

```kotlin
// build.gradle.kts
plugins {
    id("me.bechberger.test-order") version "0.0.1-SNAPSHOT"
}
```
</details>

**2. Run your tests:**

```bash
./gradlew test                          # Auto-detects and reorders
./gradlew testOrderShow                 # See ranking
./gradlew testOrderDashboard            # Interactive report
```

### .gitignore

Add to `.gitignore`:
```gitignore
.test-order/
target/test-order-dashboard/
build/test-order-dashboard/
```

<details>
<summary><strong>Optional: commit the dependency index</strong></summary>

If your learn run is slow (large projects), you can commit `.test-order/test-dependencies.lz4` so teammates and CI skip the initial learn. Otherwise, just gitignore the whole directory — the plugin auto-learns on first run.

</details>

### Maven and Gradle at a glance

| Feature | Maven | Gradle |
|---|---|---|
| Auto mode | `mvn test` (with `prepare` goal) | `./gradlew test` |
| Learn mode | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` |
| Show ranking | `mvn test-order:show` | `./gradlew testOrderShow` |
| Dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Diagnose setup | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| **Select change-affected tests** | `mvn test-order:affected test` | `./gradlew testOrderAffected` |
| Run deferred (remaining) tests | `mvn test-order:run-remaining test` | `./gradlew testOrderRunRemaining` |
| Detect flaky tests | `mvn test-order:detect-dependencies` | `./gradlew testOrderDetectDependencies` |
| **Mutation testing** | `mvn test-order:analyze-mutations` | `./gradlew testOrderAnalyzeMutations` |

For the full CLI reference, see [docs/CLI_REFERENCE.md](docs/CLI_REFERENCE.md).

## Demo

[![asciicast](https://asciinema.org/a/QhXjJtvug2nR2VVh.svg)](https://asciinema.org/a/QhXjJtvug2nR2VVh)

> *Spring Petclinic: modify a controller → that test jumps to #1 → instant feedback.*

<details>
<summary><strong>What the demo shows</strong></summary>

The recording demonstrates the full workflow on **Spring Petclinic** (~24 test classes):

1. **Learn** — `mvn test` collects which test class exercises which source class.
2. **Modify source** — Touch `OwnerController.java`; `OwnerControllerTests` jumps to rank 1.
3. **Run prioritized** — Affected tests run first, giving instant feedback.
4. **Second change** — Touch `VetController`; the order shifts automatically.
5. **Dashboard** — `mvn test-order:dashboard` generates an interactive HTML report.

</details>

## How it works

1. **Learn** — A Java agent records which source classes each test exercises. This creates a dependency index (`.test-order/test-dependencies.lz4`).
2. **Order** — On subsequent runs, the plugin detects changed files (via Git), scores each test by how much it overlaps with those changes, and runs highest-scoring tests first.

The plugin auto-switches between modes: first run learns, subsequent runs reorder. It re-learns automatically when new test classes are detected or every 10 runs (configurable).

![Dashboard Tests tab](docs/dashboard-overview.png)

<details>
<summary><strong>Change detection modes</strong></summary>

Configure via `-Dtestorder.changeMode=<mode>`:

| Mode | Behaviour | Best for |
|------|-----------|----------|
| `uncommitted` (default) | Detects staged, unstaged, and untracked changes in your working tree | Local development |
| `auto` | Uses `since-last-run` if hash snapshot exists, otherwise `since-last-commit` | Most projects |
| `since-last-commit` | Detects HEAD~1..HEAD changes + merges uncommitted changes | CI / branch validation |
| `since-last-run` | Compares file hashes against the previous test run's snapshot | CI without git history / shallow clones |
| `explicit` | Only scores classes listed in `-Dtestorder.changed.classes=...` | Scripted pipelines |

</details>

<details>
<summary><strong>Framework support</strong></summary>

| Framework | Learn mode | Order mode | Auto-discovery |
|-----------|-----------|-----------|----------------|
| **JUnit 5 / 6** | `TelemetryListener` (JUnit Platform) | `PriorityClassOrderer` + `PriorityMethodOrderer` | Via JUnit service files |
| **JUnit 4 (via Vintage)** | `TelemetryListener` (JUnit Platform) | `PriorityClassOrderer` + `PriorityMethodOrderer` | Via JUnit Vintage engine |
| **TestNG 7.x+** | `TestNGTelemetryListener` (`ITestListener`) | `TestNGPriorityInterceptor` (`IMethodInterceptor`) | Via `META-INF/services/org.testng.ITestNGListener` |
| **Kotest** | Via JUnit Platform (Kotest runner) | Via JUnit Platform | Same as JUnit |

The Maven/Gradle plugins automatically detect which framework is on the test classpath — no configuration needed.

<details>
<summary><strong>JUnit 4 with Vintage engine</strong></summary>

If your project uses JUnit 4 tests, you can run them through test-order by adding the `junit-vintage-engine` dependency. This allows JUnit 4 tests to execute on the JUnit Platform, enabling full support for learn, order, and affected-test selection — no test migration required:

```xml
<dependency>
  <groupId>org.junit.vintage</groupId>
  <artifactId>junit-vintage-engine</artifactId>
  <version>5.11.4</version>
  <scope>test</scope>
</dependency>
```

Or in Gradle: `testImplementation 'org.junit.vintage:junit-vintage-engine:5.11.4'`

</details>

> **JUnit 5 / 6 compatibility:** `test-order-junit` is compiled against JUnit 5.10.x and runs on both JUnit 5 (Jupiter 5.8+) and JUnit 6 (Jupiter 6.x) without changes. The same JAR works for both versions — no separate module needed.

**Parameterized tests** work normally. **Spring test slices** are treated as regular test classes. See [docs/KOTEST.md](docs/KOTEST.md) for Kotlin/Kotest details.

</details>

<details>
<summary><strong>Parallel execution</strong></summary>

Class-level parallelism works in order mode (Surefire `<parallel>`, JUnit concurrent mode, multiple forks). The computed order becomes a scheduling priority hint — higher-priority classes start first.

> **Note:** Learn mode requires sequential class execution (method-level parallelism is fine).

Parallel module execution (`mvn ... -T 1C`) is also supported with file-level locking.

</details>

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

## CI Integration

Cache `.test-order/` between CI runs so PRs inherit the existing index from their base branch:

```yaml
# GitHub Actions — branch-coupled cache key (does NOT bust on every push)
- uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: |
      test-order-${{ runner.os }}-
```

> **Why branch-coupled?** Keying on source files (`hashFiles(...)`) causes a cache miss on
> every commit, defeating the point. The branch key lets PRs reuse the base-branch index.

### CI workflows

| Workflow | Commands | When to use |
|---|---|---|
| **Simple** (all tests, reordered) | `mvn test` | No pipeline changes needed; any project |
| **Two-phase** (affected first, rest later) | `mvn test-order:select test` then `mvn test-order:run-remaining test` | Fast feedback for PRs |
| **Three-tier** (affected → top-scored → rest) | `mvn test-order:tiered-select test` + two `run-tier` steps | Structured CI with separate pass/fail gates |
| **Single-invocation tiered** | `mvn test-order:run-tiered test` | All tiers in one Surefire run; good for local dev |
| **Parallel sharding** (tier 3 across N runners) | `mvn test-order:run-tiered test -Dtestorder.tiered.shard=1/3` | Scale out tier-3 across parallel CI runners |

**Tiered execution benefit**: tier 1 runs only change-affected tests and fails fast within
seconds. Tier 2 runs the highest-scored remaining tests by duration budget. Only tier 3
runs everything — and only if tiers 1 and 2 pass. This gives you fast failure detection
without restructuring your existing test suite.

**Visibility flags** — add to any `mvn` invocation:
- `-Dtestorder.ci.githubStepSummary=true` — posts a test-selection summary to the GitHub Actions step summary
- `-Dtestorder.ci.prComment=true` — adds a PR comment showing which tests ran and why

> **Cold start?** Use `mvn test-order:download` to fetch the dependency index from a
> previous CI run. See [test-order-ci/README.md](test-order-ci/README.md).

For full CI examples (GitHub Actions, GitLab CI, Azure Pipelines): **[docs/ci-examples/](docs/ci-examples/)**

## Scoring

Each test gets a score based on multiple signals. Tests run in descending score order.

<!-- BEGIN WEIGHTS TABLE -->
| Signal | Default weight | What it means |
|---|---|---|
| **New test** | 15 | Test class not in the index yet |
| **Changed test** | 9 | Test source file was modified |
| **Dependency overlap** | up to 5 | Test exercises classes you changed |
| **Recent failures** | up to 5 | Test failed recently (weighted by recency) |
| **Speed bonus** | 1 | Fast tests (≤ 1/8× median) get a small bonus |
| **Speed penalty** | 1 | Slow tests (≥ 8× median) get a small penalty |
| **Change complexity** | up to 2 | Changed classes are information-dense |
| **Package proximity** | 2 | Test package matches a changed class's package (`testorder.score.packageProximityBonus`) |
| **Static field bonus** | 0 (opt-in) | Bonus when a test touches a changed static field (requires `MEMBER` mode) |
| **Coverage bonus** | 0 (opt-in) | Greedy set-cover bonus replacing `depOverlap` — set `coverageBonus > 0` to enable |
| **Kill-rate bonus** | 0 (opt-in) | Tests killing more mutants rank higher (requires `analyze-mutations`) |
<!-- END WEIGHTS TABLE -->

All weights are configurable. Run `mvn test-order:optimize` to auto-tune them for your project.

### Method-level scoring (optional)

When `testorder.methodOrder.enabled=true`, the plugin also reorders methods within each test class. This uses a separate set of 7 weights (all configurable via `testorder.method.score.*`):

| Weight | Default | What it means |
|---|---|---|
| **New method** | 5.0 | Method has no telemetry history yet |
| **Changed method** | 3.0 | Method source was modified since last run |
| **Failure recency** | 3.0 | Method failed recently |
| **Dependency overlap** | 2.0 | Method exercises changed classes (requires `METHOD` instrumentation mode) |
| **Speed bonus** | 1.0 | Method runs in ≤ 1/8× class median |
| **Speed penalty** | 1.0 | Method runs in ≥ 8× class median |
| **Coverage bonus** | 0.0 (opt-in) | Greedy set-cover bonus, replaces `depOverlap` when > 0 |

Enable via `-Dtestorder.methodOrder.enabled=true` or in Gradle DSL:
```groovy
testOrder {
    methodOrderingEnabled = true
}
```

For the full formula, weight customization (TOML), and tuning guide: **[docs/SCORING.md](docs/SCORING.md)**

## Mutation Testing (optional)

[PIT](https://pitest.org/) mutation testing measures how thoroughly your tests actually detect logic errors — it mutates source code and checks whether any test fails. test-order can use these **kill rates** as an extra scoring signal: tests that historically kill more mutants rank higher.

This is designed as an **occasional background job** (nightly / weekly) rather than something you run on every commit:

```bash
# Run learn mode first if you haven't already
mvn test -Dtestorder.mode=learn

# Run PIT scoped to the classes in your dependency index
mvn test-order:analyze-mutations

# Kill rates are now stored in the state file and used for scoring
mvn test-order:show    # killRate column now populated
```

The Gradle equivalent:

```bash
./gradlew testOrderAnalyzeMutations
```

**What it produces:**
- `target/test-mutation-results.json` — per-test kill rate breakdown
- Updated `.test-order/state.lz4` — kill rates persisted for all future ordering runs
- Dashboard **Mutation tab** — visual breakdown by kill-rate tier (high / medium / low / none)

**Enable the kill-rate scoring bonus** by setting `killRateBonus` in your scoring weights (default is 0 — no effect until you explicitly opt in):

```bash
mvn test -Dtestorder.score.killRateBonus=5
```

Or persistently via a weights file (pass `-Dtestorder.weights.file=my-weights.toml` or set `<weightsFile>` in the POM):

```toml
[killRateBonus]
value = 5
```

**For CI**, add a scheduled workflow so kill rates stay fresh as the codebase evolves. See [`.github/workflows/mutation-testing.yml`](.github/workflows/mutation-testing.yml) for a ready-to-use example that runs weekly and uploads the report as a build artifact.

> **Note:** Mutation analysis is slow on large projects. Scope it to a specific module with `-pl <module>` or limit the target classes with `-Dtestorder.mutations.targetClasses=com.example.*`. In multi-module builds the goal runs once at the reactor root and collects all module classpaths automatically. For non-standard layouts, set `-Dtestorder.mutations.classesDir` and `-Dtestorder.mutations.testClassesDir`.
>
> Full parameter reference: [docs/MAVEN_PLUGIN.md — Mutation Testing](docs/MAVEN_PLUGIN.md#mutation-testing-analyze-mutations)

### Run Quality: APFD

After each test run with failures, test-order reports the **APFD** (Average Percentage of Faults Detected) — a standard metric measuring how early in the test suite failures are surfaced:

```
[test-order] Run APFD: 92.9% (first failure at position 1/7)
[test-order] ⏱️  Estimated time saved: 21s (based on default execution order)
```

- **100% APFD** = all failures found at the very start of the run (perfect ordering)
- **50% APFD** = equivalent to random ordering
- **0% APFD** = failures found only at the very end (worst case)

The time-saved estimate compares the actual (prioritized) order against alphabetical order — the default for most test frameworks. Higher APFD means faster feedback on broken code.

## Features

| Feature | Command | Details |
|---|---|---|
| **Dashboard** | `mvn test-order:dashboard` / `mvn test-order:serve` | Interactive HTML report — test explorer, run history analytics, weight simulator, and more. See [Dashboard docs](test-order-dashboard/README.md) |
| **ML predictions** | `mvn test -Dtestorder.ml.enabled=true` | Failure probability predictions using logistic regression on test history |
| **Mutation testing** | `mvn test-order:analyze-mutations` | Run PIT mutation testing scoped to indexed classes; enriches scoring with kill rates (see below) |
| **Detect flaky tests** | `mvn test-order:detect-dependencies` | Find order-dependent tests. See [docs/DETECT_DEPENDENCIES.md](docs/DETECT_DEPENDENCIES.md) |
| **Coverage analysis** | `mvn test-order:coverage` | Identify least-tested production classes |
| **Method-level ordering** | `mvn test -Dtestorder.methodOrder.enabled=true` | Reorder methods within each class |
| **Annotations** | `@AlwaysRun` | Pin critical tests to always run first |
| **Incremental learn** | `-Dtestorder.auto.alwaysLearn=true` | Keep dependency index fresh without dedicated learn runs (preview — see below) |

<details>
<summary><strong>Dashboard screenshots</strong></summary>

**Tests tab** — ranked list with score breakdown, run history sparklines, and per-test detail panel:

![Dashboard Tests tab](docs/dashboard-tests-tab.png)

**Analytics tab** — APFD timeline, per-run drill-down, rank heatmap, failure correlation, and 15+ analysis panels:

![Dashboard Analytics tab](docs/dashboard-analytics-tab.png)

**Weights tab** — tune scoring components and instantly preview rank changes:

![Dashboard Weights tab](docs/dashboard-weights-tab.png)

Full feature reference: [test-order-dashboard/README.md](test-order-dashboard/README.md)

Additional tabs appear when data is available: **ML Health** (failure-probability model), **Mutations** (PIT kill rates), and **Static Analysis** (selective-learn instrumentation scope — classes identified by the call graph as reachable from current changes).

</details>

<details>
<summary><strong>Annotations</strong></summary>

Add the annotations dependency:

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-annotations</artifactId>
  <version>${testorder.version}</version>
  <scope>test</scope>
</dependency>
```

**`@AlwaysRun`** — Pin a test to always run first, regardless of score:

```java
import me.bechberger.testorder.annotations.AlwaysRun;

@AlwaysRun
public class SmokeTest { … }
```

JUnit's `@Order` and `@TestMethodOrder` annotations are respected — test-order won't reorder classes/methods that already have explicit ordering.

</details>

<details>
<summary><strong>Incremental learn (preview)</strong></summary>

Two flags keep the dependency index fresh without dedicated learn runs:

- **`-Dtestorder.auto.alwaysLearn=true`** — attaches the learn agent on top of every ordered run so new dependencies are recorded incrementally. After each run the newly-recorded `.deps` files are merged into the existing index (union semantics), preserving entries for tests that weren't re-instrumented.
- **`-Dtestorder.learn.selective=true`** — uses static call-graph analysis to instrument only classes reachable from the current changes (changed classes plus their transitive callees, up to 4 hops). Keeps per-run overhead proportional to the size of your change, not the size of the project.

Combine both for low-overhead background index maintenance:

```bash
# Maven (lifecycle-injected via .mvn/extensions.xml — most common setup)
mvn test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true

# Maven CLI goal (explicit)
mvn test-order:auto test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true

# Gradle (alwaysLearn supported since this release)
./gradlew test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true
```

Both flags default to `false`. When `alwaysLearn=true` and no structural changes are detected (empty uncertain set), the agent attach is skipped automatically — zero overhead on no-change runs.

</details>

## Compatibility

<details>
<summary><strong>JaCoCo / Mockito coexistence</strong></summary>

test-order and JaCoCo chain automatically — no configuration needed in most cases. Both plugins set the Maven `argLine` property, and they append to each other.

You only need `@{argLine}` if your POM already has a **hardcoded** `<argLine>` with custom JVM flags:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <!-- @{argLine} lets JaCoCo + test-order inject their agents -->
    <argLine>@{argLine} -Xmx1024m</argLine>
  </configuration>
</plugin>
```

If you don't have a custom `<argLine>`, don't add one — the plugin auto-detects and handles it.

</details>

## Known Issues

### `PluginContainerException: ... UsageStore` on multi-module builds

**Symptom:** During a learn pass on a large multi-module reactor, Maven aborts with:

```
[ERROR] -----------------------------------------------------: me.bechberger.testorder.agent.runtime.UsageStore
[ERROR] -> [Help 1] PluginContainerException
```

**Trigger:** A non-Surefire plugin (e.g. OData/OpenAPI code generators running at `generate-sources`) loads instrumented classes from `target/classes/`, but its `ClassRealm` rejected the runtime-package import.

**Status:** `RuntimeRealmInjector` now retries via a `ClassRealm.addURL` fallback when `importFrom` is rejected. Sealed realms get their own `UsageStore` copy; the build no longer crashes. A diagnostic line is logged to stderr when the fallback fires:

```
[test-order] sealed-realm fallback: realm '<plugin-id>' rejected importFrom (...) — added me.bechberger.testorder.agent.runtime via addURL
```

If the fallback also fails, a separate `[test-order] realm '...' cannot resolve me.bechberger.testorder.agent.runtime — ...` line names the offending plugin so you can `-pl '!offending-module'` it. This covers plugin realms visible at the time the lifecycle participant runs — extension realms created later in the build are still subject to the original limitation.

## Troubleshooting

Run `mvn test-order:diagnose` first — it checks everything automatically.

| Symptom | Fix |
|---|---|
| `No plugin found for prefix 'test-order'` | Add `me.bechberger` to `<pluginGroups>` in `~/.m2/settings.xml` (see [Quick Start](#quick-start)), or use the fully-qualified goal: `mvn me.bechberger:test-order-maven-plugin:0.0.1-SNAPSHOT:auto test` |
| "Wrote fallback payloads" every run | Add `<extensions>true</extensions>` to the plugin declaration in `pom.xml` (see FAQ below) |
| Tests always run in default order | The first learn run may not have completed. Check `.test-order/test-dependencies.lz4` exists. If not, re-run `mvn test`. |
| All test scores are 0, no reordering | Run with `-Dtestorder.debug=true` — likely no changed classes were detected. Check `testorder.changeMode`. |
| `No dependency index found` on second run | Ensure the first run completed successfully and `.test-order/test-dependencies.lz4` was written |
| JaCoCo reports 0% coverage | Usually works automatically; if you have a hardcoded `<argLine>` in Surefire, change it to `@{argLine}` (see Compatibility above) |
| Stale ordering after major refactor | Re-learn: `mvn test -Dtestorder.mode=learn` |
| No index despite running learn | Source packages not detected — set `-Dtestorder.includePackages=com.yourcompany` |
| `Could not resolve artifact` | Plugin not yet published to Maven Central — build from source (see [Development](#development)) |
| Agent attachment warning on Java 21+ | Add `--add-opens` flags to the Surefire `<argLine>` |
| "Failed to load JUnit Platform" (Gradle) | Snapshot repo added to project `repositories {}` instead of `pluginManagement.repositories {}` | Move it to `pluginManagement.repositories {}` only — see Gradle setup above |
| Tests skip unexpectedly on first run | No index yet — `affected` and tiered goals fall back to running all tests on cold start |

Nuclear option: `rm -rf .test-order && mvn test -Dtestorder.mode=learn`

## FAQ

<details>
<summary><strong>Does test-order skip tests?</strong></summary>

No. By default, all tests still run — they're just **reordered** so the most relevant ones execute first. If a test fails, you see it sooner. If you want to actually select a subset, use `mvn test-order:affected test` (runs only high-priority tests) followed by `mvn test-order:run-remaining test`.

</details>

<details>
<summary><strong>Is there runtime overhead?</strong></summary>

- **Order mode** (normal runs): Near-zero. The plugin resolves ordering before tests start.
- **Learn mode** (first run or when new test classes are added): ~5–30% overhead from the Java agent recording test dependencies. In `auto` mode (the default), this triggers automatically on first run, when new test classes are detected, and every 10 order-mode runs (`autoLearnRunThreshold`, configurable). Re-running learn manually refreshes the index after significant refactors.

The overhead depends on the instrumentation mode (see below). MEMBER mode (the default) is heavier than CLASS mode but produces more accurate dependency data.

</details>

<details>
<summary><strong>Can I control how much dependency data is recorded?</strong></summary>

Yes, via `testorder.instrumentation.mode`. Three modes are available:

| Mode | What it records | Index size | Learn overhead | Accuracy |
|---|---|---|---|---|
| `MEMBER` *(default)* | Which fields/methods of each class are accessed | Larger | ~10–30% | Highest — precise member-level overlap |
| `CLASS` | Which classes are loaded | Smallest | ~5–15% | Good — class-level overlap |
| `METHOD` | Which methods are entered | Medium | ~10–20% | High — method-level overlap |

MEMBER mode is the default because it produces the most accurate dependency data: a test only "overlaps" a changed class if it actually calls a member that changed, not just if it happens to load the class. This matters for utility classes used everywhere — the test that *directly exercises* the changed code ranks significantly higher.

If your index files grow too large (check `.test-order/test-dependencies.lz4`) or learn mode takes too long, switch to CLASS mode:

```bash
# Maven
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=CLASS

# Gradle
./gradlew test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=CLASS
```

</details>

<details>
<summary><strong>Does it work with my existing build?</strong></summary>

Yes. test-order integrates through standard build tool mechanisms (JUnit Platform `ClassOrderer`, Surefire/Failsafe configuration). It doesn't modify your source code, test code, or build output. Remove the plugin declaration to go back to the default behavior instantly.

</details>

<details>
<summary><strong>What if I don't use Git?</strong></summary>

Git is the default change detection backend. Without Git, you can use `-Dtestorder.changeMode=since-last-run` (compares file hashes between runs) or `-Dtestorder.changeMode=explicit` with `-Dtestorder.changed.classes=...`.

</details>

<details>
<summary><strong>Does it work with multi-module projects?</strong></summary>

Yes. Each module gets its own dependency index. For reactor-level features (e.g., skipping entire modules that have no relevant changes), see [Multi-Module Setup](docs/MULTI_MODULE_SETUP.md).

</details>

<details>
<summary><strong>How do I share the index with my team?</strong></summary>

Commit `.test-order/test-dependencies.lz4` to version control. Teammates and CI will immediately benefit from the learned dependency index without needing a learn run. Add the machine-local files (`hashes.lz4`, `method-hashes.lz4`, `test-hashes.lz4`) to `.gitignore`.

</details>

<details>
<summary><strong>Can I use it alongside JaCoCo, Mockito, or other agents?</strong></summary>

Yes. JaCoCo and test-order chain automatically with no configuration. Only if you have a hardcoded `<argLine>` in Surefire do you need `@{argLine}` — see [Compatibility](#compatibility).

</details>

<details>
<summary><strong>How do I survive <code>git clean</code>?</strong></summary>

By default, test-order stores data in `.test-order/` inside your project, which `git clean -fdx` will delete. To persist across cleans, commit the `.test-order/` directory to version control (add it to `.gitignore` exceptions), or configure the paths to point outside the project tree:

```xml
<!-- pom.xml — store index and state outside the working tree -->
<configuration>
  <indexFile>${user.home}/.test-order/${project.artifactId}/test-dependencies.lz4</indexFile>
  <stateFile>${user.home}/.test-order/${project.artifactId}/state.lz4</stateFile>
</configuration>
```

</details>

<details>
<summary><strong>Every learn run prints "Wrote fallback payloads" — is something broken?</strong></summary>

No, but it means the Maven lifecycle extension is not active. Add `<extensions>true</extensions>` to the plugin declaration:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>...</version>
  <extensions>true</extensions>   <!-- ← add this -->
  ...
</plugin>
```

Without it, `CollectorLifecycleParticipant.afterSessionEnd()` never runs, so the JVM shutdown hook writes a `.collector-fallback` file instead. The fallback is processed correctly on the next build, so results are still correct — but you see the noisy message on every run and the index is written one build late. Adding `<extensions>true</extensions>` makes the learn pass write the index directly without the fallback.

</details>

## AI Coding Assistant Integration

If you use GitHub Copilot, Claude Code, or similar AI coding assistants, add the following to your project instructions (`.github/copilot-instructions.md`, `CLAUDE.md`, etc.) so the assistant knows how to run tests efficiently:

<details>
<summary><strong>Suggested instructions snippet</strong></summary>

```markdown
## Running Tests

This project uses test-order for affected-test selection.

- **Quick check** (few files changed): `mvn test-order:affected test`
  Runs only tests affected by uncommitted changes. Use for fast feedback.
- **After large changes** (new dependencies, refactors): `mvn test-order:learn test`
  Rebuilds the dependency index. Required after major structural changes.
- **Normal run**: `mvn test`
  Auto-detects whether to learn or order.
- **Run deferred tests**: `mvn test-order:run-remaining test`
  Runs tests that were skipped by `affected`.

Change detection: `-Dtestorder.changeMode=since-last-commit` for CI,
`uncommitted` (default) for local development.
```

</details>

### Driving test-order from an LLM agent

For agents that invoke the plugin programmatically (Claude Code, Cursor, Aider, custom orchestrators), test-order ships first-class agentic affordances:

- **[`docs/AGENTS.md`](docs/AGENTS.md)** — the agent-facing guide: how to invoke, error recovery, stability tiers.
- **[`docs/agent-manifest.json`](docs/agent-manifest.json)** — machine-readable task inventory (every goal/task with stability, JSON-output capability, example invocation). Bundled into both plugin JARs as a classpath resource so an agent can fetch it from the project under test:

  ```bash
  mvn -B -ntp -q test-order:help -Dtestorder.help.format=json     # Maven
  ./gradlew --quiet testOrderHelp -Dtestorder.help.format=json    # Gradle
  ```

  Both print the manifest as parseable JSON to **stdout**.
- **`Run:` lines in error messages** — recoverable errors append literal `Run: <command>` lines. An agent greps for `^Run: ` and executes the suggested command. Example: a missing index produces `Run: mvn test -Dtestorder.mode=learn` followed by `Run: mvn test-order:diagnose`.

#### Install the Claude Code skill

The repo includes a [Claude Code skill](https://docs.claude.com/en/docs/claude-code/skills) that teaches Claude how to drive test-order — JSON manifest discovery, the `Run:`-line recovery convention, and which tasks emit parseable JSON. Install it once and Claude will load it automatically when you ask it to run, prioritise, or debug tests in any project that uses test-order.

```bash
# Personal install (all your projects)
mkdir -p ~/.claude/skills
cp -R skills/test-order ~/.claude/skills/

# or, to track upstream changes:
ln -s "$PWD/skills/test-order" ~/.claude/skills/test-order
```

After install, Claude picks it up automatically based on the skill's `description`. To invoke it explicitly, type `/test-order` in your Claude Code session. The skill source lives at [`skills/test-order/SKILL.md`](skills/test-order/SKILL.md) — copy or adapt it for other agents that support the [Agent Skills](https://agentskills.io) standard.

## Documentation

| Guide | Description |
|---|---|
| **[Cheat Sheet](docs/CHEAT_SHEET.md)** | One-page quick reference: commands, properties, troubleshooting |
| **[Getting Started](docs/GETTING_STARTED.md)** | 5-minute tutorial: first run → reordering → dashboard |
| **[CLI Reference](docs/CLI_REFERENCE.md)** | All goals, properties, and configuration options |
| **[Maven Plugin](docs/MAVEN_PLUGIN.md)** | Full Maven goal reference and CI YAML examples |
| **[Scoring](docs/SCORING.md)** | Formula details, weight customization, auto-tuning |
| **[Multi-Module Setup](docs/MULTI_MODULE_SETUP.md)** | Reactor-level aggregation for multi-module projects |
| **[CI Examples](docs/ci-examples/)** | Ready-to-use GitHub Actions, GitLab CI, Azure configs |
| **[Detect Dependencies](docs/DETECT_DEPENDENCIES.md)** | Order-dependent (flaky) test detection |
| **[Kotest](docs/KOTEST.md)** | Kotlin & Kotest integration |
| **[For LLM agents](docs/AGENTS.md)** | Driving test-order from an agent — manifest discovery, error recovery |
| **[Architecture](docs/ARCHITECTURE.md)** | System design and contribution guidance |
| **[Samples](samples/README.md)** | Example projects to experiment with |

## Development

```bash
git clone https://github.com/parttimenerd/test-order.git
cd test-order && mvn install -DskipTests -Dspotless.check.skip=true
```

This installs the SNAPSHOT to your local Maven repository. Full build instructions: **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for PR guidelines, code style, and release process. For building from source, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Support & Feedback

Bug reports, feature requests, and contributions are welcome via [GitHub issues](https://github.com/parttimenerd/test-order/issues).

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors
