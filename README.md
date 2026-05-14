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
| Test framework | JUnit 5/6, TestNG 7.x+, or Kotest (JUnit Platform) |

<details>
<summary><strong>Check prerequisites</strong></summary>

```bash
java -version   # Must be 17+
mvn --version   # Must be 3.6+
git --version   # Any recent version
```

</details>

## Quick Start

### Maven

**1. Add the plugin** to your `pom.xml` inside `<build><plugins>`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>
```

**2. Run your tests twice:**

```bash
mvn test          # First run: learns which tests cover which code
mvn test          # Second run: reorders — affected tests run first
```

That's it. The plugin auto-switches between learn and order mode. No configuration needed.

**3. Explore (optional):**

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
        mavenLocal()       // needed until published to Gradle Plugin Portal
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

### Maven vs Gradle at a glance

| Feature | Maven | Gradle |
|---|---|---|
| Auto mode | `mvn test` (with `prepare` goal) | `./gradlew test` |
| Learn mode | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` |
| Show ranking | `mvn test-order:show` | `./gradlew testOrderShow` |
| Dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Diagnose setup | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| Select subset | `mvn test-order:select test` | `./gradlew testOrderSelect` |
| Detect flaky tests | `mvn test-order:detect-dependencies` | `./gradlew testOrderDetectDependencies` |

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

The plugin auto-switches between modes: first run learns, subsequent runs reorder. It re-learns automatically when it detects significant classpath changes.

<details>
<summary><strong>Change detection modes</strong></summary>

Configure via `-Dtestorder.changeMode=<mode>`:

| Mode | Behaviour | Best for |
|------|-----------|----------|
| `uncommitted` (default) | Detects staged, unstaged, and untracked changes in your working tree | Local development |
| `auto` | Uses `since-last-run` if hash snapshot exists, otherwise `since-last-commit` | Most projects |
| `since-last-commit` | Compares working tree against `HEAD` via `git diff` | CI / branch validation |
| `since-last-run` | Compares file hashes against the previous test run's snapshot | CI without git history / shallow clones |
| `explicit` | Only scores classes listed in `-Dtestorder.changed.classes=...` | Scripted pipelines |

</details>

<details>
<summary><strong>Framework support</strong></summary>

| Framework | Learn mode | Order mode | Auto-discovery |
|-----------|-----------|-----------|----------------|
| **JUnit 5 / 6** | `TelemetryListener` (JUnit Platform) | `PriorityClassOrderer` + `PriorityMethodOrderer` | Via JUnit service files |
| **TestNG 7.x+** | `TestNGTelemetryListener` (`ITestListener`) | `TestNGPriorityInterceptor` (`IMethodInterceptor`) | Via `META-INF/services/org.testng.ITestNGListener` |
| **Kotest** | Via JUnit Platform (Kotest runner) | Via JUnit Platform | Same as JUnit |

The Maven/Gradle plugins automatically detect which framework is on the test classpath — no configuration needed.

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

Cache `.test-order/` between CI runs so PRs benefit from the existing index:

```yaml
# GitHub Actions
- uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/src/**/*.java') }}
    restore-keys: test-order-${{ runner.os }}-
```

### CI workflows

| Workflow | Commands |
|---|---|
| **Simple** (all tests, reordered) | `mvn test` |
| **Two-phase** (affected first, rest later) | `mvn test-order:select test` then `mvn test-order:run-remaining test` |
| **Three-tier** (fastest feedback → broader → full) | See [docs/ci-examples/](docs/ci-examples/) |

> **Cold start?** Use `mvn test-order:download` to fetch the dependency index from a previous CI run. See [test-order-ci/README.md](test-order-ci/README.md).

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
| **Speed** | ±1 | Fast tests get a small bonus; slow tests a penalty |
| **Change complexity** | up to 2 | Changed classes are information-dense |
<!-- END WEIGHTS TABLE -->

All weights are configurable. Run `mvn test-order:optimize` to auto-tune them for your project.

For the full formula, weight customization (TOML), and tuning guide: **[docs/SCORING.md](docs/SCORING.md)**

## Features

| Feature | Command | Details |
|---|---|---|
| **Dashboard** | `mvn test-order:dashboard` | Interactive HTML report with test explorer and weight simulator |
| **ML predictions** | `mvn test -Dtestorder.ml.enabled=true` | Failure probability predictions using logistic regression on test history |
| **Detect flaky tests** | `mvn test-order:detect-dependencies` | Find order-dependent tests. See [docs/DETECT_DEPENDENCIES.md](docs/DETECT_DEPENDENCIES.md) |
| **Coverage analysis** | `mvn test-order:coverage` | Identify least-tested production classes |
| **Method-level ordering** | `mvn test -Dtestorder.methodOrder.enabled=true` | Reorder methods within each class |
| **Annotations** | `@AlwaysRun` | Pin critical tests to always run first |

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

## Compatibility

<details>
<summary><strong>JaCoCo / Mockito coexistence</strong></summary>

If your POM defines a custom `<argLine>`, use Maven's late-binding syntax to chain agents:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>@{argLine} -Xmx1024m</argLine>
  </configuration>
</plugin>
```

The plugin auto-detects hardcoded `<argLine>` and falls back gracefully, but `@{argLine}` is recommended.

</details>

## Troubleshooting

Run `mvn test-order:diagnose` first — it checks everything automatically.

| Symptom | Fix |
|---|---|
| Tests in default order | Run with `-Dtestorder.debug=true` to see what's happening |
| `No dependency index found` on second run | Ensure the first run completed successfully and `.test-order/test-dependencies.lz4` exists |
| JaCoCo reports 0% coverage | Use `@{argLine}` syntax (see Compatibility above) |
| Stale ordering after refactor | Re-learn: `mvn test -Dtestorder.mode=learn` |
| No index despite sources | Set `-Dtestorder.includePackages=com.yourcompany` |
| `Could not resolve artifact` | Plugin not published to Maven Central yet — build from source (see [Development](#development)) |
| Agent attachment warning on Java 21+ | Add `--add-opens` flags or use `-Dtestorder.agent.dynamic=false` |

Nuclear option: `rm -rf .test-order && mvn test -Dtestorder.mode=learn`

## FAQ

<details>
<summary><strong>Does test-order skip tests?</strong></summary>

No. By default, all tests still run — they're just **reordered** so the most relevant ones execute first. If a test fails, you see it sooner. If you want to actually select a subset, use `mvn test-order:select test` (runs only high-priority tests) followed by `mvn test-order:run-remaining test`.

</details>

<details>
<summary><strong>Is there runtime overhead?</strong></summary>

- **Order mode** (normal runs): Near-zero. The plugin resolves ordering before tests start.
- **Learn mode** (first run or after dependency changes): ~5–20% overhead from the Java agent recording class coverage. This runs automatically and infrequently.

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

Yes. Use Maven's `@{argLine}` late-binding in Surefire to chain multiple agents. See [Compatibility](#compatibility) above.

</details>

## Documentation

| Guide | Description |
|---|---|
| **[Getting Started](docs/GETTING_STARTED.md)** | 5-minute tutorial: first run → reordering → dashboard |
| **[CLI Reference](docs/CLI_REFERENCE.md)** | All goals, properties, and configuration options |
| **[Maven Plugin](docs/MAVEN_PLUGIN.md)** | Full Maven goal reference and CI YAML examples |
| **[Scoring](docs/SCORING.md)** | Formula details, weight customization, auto-tuning |
| **[Multi-Module Setup](docs/MULTI_MODULE_SETUP.md)** | Reactor-level aggregation for multi-module projects |
| **[CI Examples](docs/ci-examples/)** | Ready-to-use GitHub Actions, GitLab CI, Azure configs |
| **[Detect Dependencies](docs/DETECT_DEPENDENCIES.md)** | Order-dependent (flaky) test detection |
| **[Kotest](docs/KOTEST.md)** | Kotlin & Kotest integration |
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

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub issues](https://github.com/parttimenerd/test-order/issues).
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors


