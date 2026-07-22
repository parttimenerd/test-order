# Building & Using the Development Version

This guide helps you build test-order from source and use the `0.1.0` in your own projects.

## Prerequisites

- Java 17 or newer (17, 21, 25, 27 tested in CI)
- Maven 3.9+
- Git
- Optional: Gradle 7.6+ (for Gradle plugin development)

## Build from source

```bash
git clone https://github.com/parttimenerd/test-order.git
cd test-order
mvn install -DskipTests -Dspotless.check.skip=true
```

This installs all modules into your local Maven repository (`~/.m2/repository`). The build takes ~30 seconds without tests.

### Gradle plugin

The Gradle plugin lives in a separate directory (not a Maven module):

```bash
cd test-order-gradle-plugin
./gradlew publishToMavenLocal
```

## Use the SNAPSHOT in your project

### Maven

Add the plugin to your project's `pom.xml`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0</version>
  <extensions>true</extensions>  <!-- required: registers the lifecycle participant that writes the index -->
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>
```

No repository configuration needed — Maven resolves SNAPSHOTs from `~/.m2/repository` by default.

### Gradle

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

// build.gradle
plugins {
    id 'me.bechberger.test-order' version '0.1.0'
}

repositories {
    mavenLocal()
    mavenCentral()
}
```

## Try it on a sample project

```bash
cd samples/sample-basic
mvn test -Dspotless.check.skip=true          # learn mode (first run)
mvn test -Dspotless.check.skip=true          # order mode (subsequent runs)
mvn test-order:show -Dspotless.check.skip=true   # inspect prioritization
mvn test-order:dashboard -Dspotless.check.skip=true  # interactive HTML report
```

## Common development commands

| Task | Command |
|------|---------|
| Full build (skip tests) | `mvn install -DskipTests -Dspotless.check.skip=true` |
| Unit tests only | `mvn test -Dspotless.check.skip=true` |
| Single module | `mvn test -pl test-order-core -Dspotless.check.skip=true` |
| Integration tests | `mvn clean install -DskipTests && mvn verify -pl test-order-maven-plugin -Dtestorder.it=true -Dspotless.check.skip=true` |
| Gradle plugin tests | `cd test-order-gradle-plugin && ./gradlew test` |
| Code quality | `mvn verify -Pquality -Dspotless.check.skip=true` |
| Error Prone analysis | `mvn verify -Pquality-errorprone` |
| Rebuild dashboard UI | `cd test-order-dashboard/src/main/dashboard && npm run build` |
| Dashboard UI tests | `mvn verify -pl test-order-dashboard-ui-tests -Dtestorder.ui=true` |
| Format code | `mvn spotless:apply` |

## Project modules

```
test-order-agent/             Java agent (bytecode instrumentation)
test-order-annotations/       @AlwaysRun, @TestOrder annotations
test-order-core/              Core engine: index, scoring, change detection, CLI
test-order-junit/             JUnit 5/6 extension and orderers
test-order-testng/            TestNG listener and interceptor
test-order-maven-plugin/      Maven plugin (all goals)
test-order-gradle-plugin/     Gradle plugin (separate build)
test-order-ci/                CI artifact downloader
test-order-dashboard/         Dashboard HTML generator + frontend
test-order-benchmarks/        Performance benchmarks
```

## Iterating on changes

After modifying a module, rebuild and test in one step:

```bash
# Rebuild only the modules you changed + dependents
mvn install -pl test-order-core,test-order-maven-plugin -am -DskipTests -Dspotless.check.skip=true

# Re-run a sample to verify
cd samples/sample-basic && mvn test-order:show -Dspotless.check.skip=true
```

## See also

- [CONTRIBUTING.md](https://github.com/parttimenerd/test-order/blob/main/CONTRIBUTING.md) — PR guidelines, code style, release process
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — system design and data flow

## Releasing a version

Releases are managed by `release.py` in the project root. The script handles:

1. Bumping versions in **all** POMs, README, docs, Gradle build files, and samples
2. Rolling CHANGELOG.md's `[Unreleased]` into a dated version entry
3. Running tests and building release artifacts
4. Creating a git commit + tag (`vX.Y.Z`)
5. Deploying to Maven Central (Sonatype OSSRH)
6. Bumping to the next `-SNAPSHOT` and committing
7. Pushing and creating a GitHub Release

### Quick release

```bash
# Patch release (0.0.1 → 0.0.2)
python release.py --patch

# Minor release (0.0.1 → 0.1.0)
python release.py --minor

# Preview what would change without modifying anything
python release.py --patch --dry-run
```

### What `release.py` updates automatically

| File(s) | What changes |
|---------|--------------|
| `pom.xml` (root) | `<version>` |
| All module `pom.xml` files | `<parent><version>` |
| All sample/fixture `pom.xml` | `<version>` in `me.bechberger` blocks |
| `README.md` | Plugin snippet versions, Gradle DSL versions |
| `docs/*.md` | All `me.bechberger` version references, `<test-order.version>` properties |
| `test-order-gradle-plugin/build.gradle.kts` | `version = "..."` |
| `test-order-gradle-plugin/test-order-init.gradle` | classpath version |
| `CHANGELOG.md` | `[Unreleased]` → `[X.Y.Z] - date` |

After a release, the script automatically bumps everything to the next SNAPSHOT (e.g., `0.0.2-SNAPSHOT`) so the README and docs always show the current development version.

### SNAPSHOT deployment

SNAPSHOTs are deployed automatically by CI on every push to `main` (after tests pass). You can also deploy manually:

```bash
python release.py --snapshot
```

### Options reference

| Flag | Effect |
|------|--------|
| `--major` / `--minor` / `--patch` | Bump level (default: minor) |
| `--no-its` | Skip integration tests during release |
| `--no-push` | Don't push to remote |
| `--no-deploy` | Skip Maven Central deploy |
| `--no-github-release` | Skip GitHub Release creation |
| `--snapshot` | Deploy current SNAPSHOT as-is |
| `--github-release-only` | Only create a GitHub Release for the current version |
| `--dry-run` | Preview changes without modifying files |

### CI release workflow

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds and deploys to Maven Central with GPG signing. Required secrets: `OSSRH_USERNAME`, `OSSRH_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. Optional: `SAMPLE_REPO_TOKEN` — a PAT with `repo` scope on `parttimenerd/sample-ci-test-order`; when set, CI triggers a rebuild of that repo after each snapshot/release deploy. Omit it if you don't have that repo.
