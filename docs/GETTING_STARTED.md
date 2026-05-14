# Getting Started with test-order

This tutorial walks you through using test-order from scratch. By the end, you'll have tests running in priority order with affected tests surfacing first.

**Time required:** ~5 minutes.

## Prerequisites

- **Java 17+** installed
- **Maven 3.6+** or **Gradle 7.6+**
- A **Git** repository (test-order uses Git to detect changes)
- A project with **JUnit 5/6**, **TestNG 7.x+**, or **Kotest** tests

---

## Step 1: Add the plugin

### Maven

Add to your `pom.xml` inside `<build><plugins>`:

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

### Gradle

Add to your `build.gradle`:

```groovy
plugins {
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT'
}
```

> **Settings:** If using a SNAPSHOT or local build, add `mavenLocal()` to your `pluginManagement.repositories` in `settings.gradle`.

<details>
<summary><b>Alternative: Gradle init script (no build file changes)</b></summary>

If you want to try test-order without modifying any build files, save this as `test-order-init.gradle`:

```groovy
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath 'me.bechberger:test-order-gradle-plugin:0.0.1-SNAPSHOT'
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

Then run any Gradle command with `--init-script path/to/test-order-init.gradle`.

</details>

---

## Step 2: Run tests (learn mode)

On the first run, the plugin automatically enters **learn mode** — it attaches a Java agent that records which application classes each test exercises.

```bash
# Maven
mvn test

# Gradle
./gradlew test
```

**What you'll see:**

```
[INFO] [test-order] No dependency index found — entering learn mode
[INFO] [test-order] Agent attached, recording dependencies...
...
[INFO] [test-order] Wrote dependency index: .test-order/test-dependencies.lz4 (42 classes, 8 test classes)
[INFO] BUILD SUCCESS
```

This creates a `.test-order/` directory with the dependency index. Commit `test-dependencies.lz4` to version control so teammates and CI benefit immediately.

---

## Step 3: Make a change and re-run

Edit a source file — for example, modify a method in one of your service classes. Then run tests again:

```bash
# Maven
mvn test

# Gradle
./gradlew test
```

**What you'll see:**

```
[INFO] [test-order] Order mode — 2 changed classes detected (uncommitted)
[INFO] [test-order] Reordered 8 test classes by priority score
[INFO] Running com.example.ServiceTest        ← runs first (exercises changed code)
[INFO] Running com.example.ControllerTest     ← second priority
[INFO] Running com.example.UtilTest           ← unrelated, runs last
...
[INFO] BUILD SUCCESS
```

Tests that exercise your changed code now run first. If something breaks, you'll know within seconds — not after the full suite completes.

---

## Step 4: Inspect the prioritization

See exactly how tests are ranked:

```bash
# Maven
mvn test-order:show

# Gradle
./gradlew testOrderShow
```

**Example output:**

```
 # │ Score │ Class                           │ Reason
───┼───────┼─────────────────────────────────┼──────────────────────────
 1 │  14.0 │ com.example.ServiceTest         │ dep-overlap=5, changed-test=9
 2 │   5.0 │ com.example.ControllerTest      │ dep-overlap=5
 3 │   1.0 │ com.example.UtilTest            │ speed-bonus=1
 4 │   0.0 │ com.example.IntegrationTest     │ (no signal)
```

---

## Step 5: View the dashboard

Generate an interactive HTML report:

```bash
# Maven
mvn test-order:dashboard
open target/test-order-dashboard/index.html

# Gradle
./gradlew testOrderDashboard
open build/test-order-dashboard/index.html
```

The dashboard shows test dependencies, scoring breakdown, run history, and (with ML enabled) failure predictions.

---

## Step 6: Diagnose issues

If something doesn't look right:

```bash
# Maven
mvn test-order:diagnose

# Gradle
./gradlew testOrderDiagnose
```

This checks index health, agent attachment, framework detection, and configuration issues.

---

## What to commit

| Path | Commit? | Why |
|------|---------|-----|
| `.test-order/test-dependencies.lz4` | **Yes** | Dependency index — shared by team and CI |
| `.test-order/state.lz4` | Optional | Test durations & failure history (improves scoring) |
| `.test-order/hashes.lz4` | No | Machine-local hash snapshots |
| `target/test-order-dashboard/` | No | Regenerated on demand |

Add to `.gitignore`:

```gitignore
.test-order/hashes.lz4
.test-order/method-hashes.lz4
.test-order/test-hashes.lz4
target/test-order-dashboard/
```

---

## Try it hands-on

The project includes sample projects you can experiment with immediately:

```bash
# Clone and build test-order (see docs/DEVELOPMENT.md for details)
git clone https://github.com/parttimenerd/test-order.git
cd test-order
mvn install -DskipTests -Dspotless.check.skip=true

# Run the basic sample
cd samples/sample-basic
mvn test -Dspotless.check.skip=true         # learns dependencies
mvn test -Dspotless.check.skip=true         # reorders
mvn test-order:show -Dspotless.check.skip=true  # inspect

# Try the realistic shopping app sample
cd ../sample-shop
mvn test -Dspotless.check.skip=true
mvn test-order:dashboard -Dspotless.check.skip=true
```

---

## Next steps

| Goal | Guide |
|------|-------|
| Set up CI with tiered testing | [docs/ci-examples/](ci-examples/) |
| Configure multi-module projects | [docs/MULTI_MODULE_SETUP.md](MULTI_MODULE_SETUP.md) |
| Tune scoring weights | [docs/SCORING.md](SCORING.md) |
| Enable ML failure predictions | [docs/MAVEN_PLUGIN.md](MAVEN_PLUGIN.md) (ML section) |
| Detect order-dependent (flaky) tests | [docs/DETECT_DEPENDENCIES.md](DETECT_DEPENDENCIES.md) |
| Build from source / contribute | [docs/DEVELOPMENT.md](DEVELOPMENT.md) |
| Full CLI & properties reference | [docs/CLI_REFERENCE.md](CLI_REFERENCE.md) |
