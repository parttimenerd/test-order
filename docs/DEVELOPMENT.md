# Building & Using the Development Version

This guide helps you build test-order from source and use the `0.1.0-SNAPSHOT` in your own projects.

## Prerequisites

- Java 17 or newer (17–24 supported; 25+ experimental)
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
  <version>0.0.1-SNAPSHOT</version>
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
    id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT'
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

- [CONTRIBUTING.md](../CONTRIBUTING.md) — PR guidelines, code style, release process
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — system design and data flow
