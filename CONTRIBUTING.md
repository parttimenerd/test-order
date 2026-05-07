# Contributing

## Requirements

- Java 17 or higher
- Maven 3.9+ (for Maven plugin) or Gradle 7.6+ (for Gradle plugin)

## Dependencies

- [femtocli](https://github.com/parttimenerd/femtocli) — CLI and agent-args parsing
- [javassist](https://www.javassist.org/) — bytecode instrumentation
- [lz4-java](https://github.com/yawkat/lz4-java) — LZ4 Frame compression for file hash snapshots
- [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap) — compressed bitmap sets for the dependency index
- JUnit 5 or 6 (provided scope)

## Development setup

- Java 17 or newer
- Maven 3.9+
- Git
- Optional: Gradle for `test-order-gradle-plugin`

Install dependencies and run the main validation paths from the repository root:

```bash
mvn test
mvn verify -pl test-order-maven-plugin -Dtestorder.it=true
cd test-order-gradle-plugin && ./gradlew test
```

## Testing

Run all tests:

```bash
./run-tests.sh
```

### Unit tests

```bash
mvn test
```

### End-to-end integration tests

```bash
mvn clean install -DskipTests && mvn verify -Dtestorder.it=true -pl test-order-maven-plugin
```

### Gradle integration tests against Spring Boot

```bash
cd test-order-gradle-plugin
JAVA_HOME="$JAVA_21_HOME" ./gradlew test \
  --tests me.bechberger.testorder.gradle.SpringBootCoreModulesIT \
  -Dtestorder.it=true \
  -Dtestorder.java.21.home="$JAVA_21_HOME" \
  -Dtestorder.java.25plus.home="$JAVA_25_PLUS_HOME"
```

Prerequisites: `third-party/spring-boot/` must exist (run `bash scripts/setup-example-repos.sh`), Java 17-24 for building, Java 25+ for the Spring Boot run.

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

## Change guidelines

- Keep changes scoped to the module you are touching.
- Prefer targeted regression tests when fixing ordering, persistence, or instrumentation bugs.
- Preserve support for both JUnit 5 and JUnit 6 unless the change explicitly narrows compatibility.
- Treat generated state and local benchmark artifacts as disposable local output, not committed source.

## Pull requests

1. Explain the user-visible behavior change.
2. Call out compatibility risks, especially around agents, JUnit ordering, and build plugins.
3. Include the validation path you used for Maven, Gradle, or integration fixtures as appropriate.

## Project structure

```
test-order-agent/             Java agent (bytecode instrumentation via javassist)
test-order-annotations/       Lightweight annotations (@AlwaysRun, @TestOrder)
test-order-core/              Core engine: dependency index, scoring, change detection, CLI tool
test-order-junit/             JUnit extension and listeners/orderers
test-order-testng/            TestNG listener and interceptor
test-order-maven-plugin/      Maven plugin (prepare, aggregate, snapshot, coverage goals)
test-order-gradle-plugin/     Gradle plugin (learn, order, utility tasks)
test-order-ci/                CI artifact downloader (GitHub Actions, GitLab CI, HTTP support)
test-order-dashboard/         Dashboard HTML generator and embedded frontend
test-order-benchmarks/        Performance benchmarks
test-order-dashboard-ui-tests/ Playwright-based E2E tests for the dashboard
test-order-example/           Example projects (Maven, Gradle, Kotlin, service)
```

## Releasing

Releases are automated via `release.py`. It bumps versions across all POMs
(root, modules, samples, fixtures, Gradle plugin), rolls the CHANGELOG, runs
tests, deploys to Maven Central, tags, and pushes.

```bash
# Standard minor release
python release.py

# Patch release, skip integration tests
python release.py --patch --no-its

# Preview what would change
python release.py --dry-run

# Deploy current SNAPSHOT without releasing
python release.py --snapshot
```

The script creates two commits (release + next-SNAPSHOT bump) and a signed tag.
On failure it automatically reverts local changes and removes the tag.
