# Extending test-order

Guide for contributors and integrators who want to extend or customize test-order behavior.

## Extension Points

test-order is designed with extension in mind. The main extension points are:

1. **Change Detection Strategies** – plug in custom change detectors
2. **Test Rankers** – customize how tests are scored/prioritized
3. **Index Persistence** – use alternative storage formats
4. **CI Downloaders** – support new CI artifact sources
5. **Coverage Reporters** – add custom output formats

---

## 1. Custom Change Detector

### Interface

```java
package me.bechberger.testorder.changes;

public interface ChangeDetectionStrategy {
    /**
     * Returns the set of fully-qualified class names that have changed.
     * @param context Provides project root, source directories, state file path, etc.
     */
    Set<String> getChangedClasses(ChangeDetectionContext context) throws IOException;
}
```

### Context Object

```java
public class ChangeDetectionContext {
    public Path getProjectRoot();         // e.g. /my-project
    public List<Path> getSourceRoots();   // e.g. [src/main/java]
    public Path getStateFilePath();       // .test-order-state
    public Path getHashFilePath();        // .test-order-hashes.lz4
    public Optional<String> getGitBaseBranch(); // "main"
}
```

### Example: Detect Changes from a File Manifest

```java
package com.example.testorder;

import me.bechberger.testorder.changes.ChangeDetectionStrategy;
import me.bechberger.testorder.changes.ChangeDetectionContext;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Reads changed class names from a manifest file produced by an external tool.
 * The manifest contains one FQCN per line:
 *   com.example.PaymentService
 *   com.example.OrderProcessor
 */
public class ManifestChangeDetector implements ChangeDetectionStrategy {

    private final Path manifestFile;

    public ManifestChangeDetector(Path manifestFile) {
        this.manifestFile = manifestFile;
    }

    @Override
    public Set<String> getChangedClasses(ChangeDetectionContext context) throws IOException {
        if (!Files.exists(manifestFile)) {
            return Set.of(); // nothing changed
        }
        return Files.readAllLines(manifestFile).stream()
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toSet());
    }
}
```

### Registering via SPI

Create `META-INF/services/me.bechberger.testorder.changes.ChangeDetectionStrategy`:

```
com.example.testorder.ManifestChangeDetector
```

### Registering via Maven Parameter

```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <changeDetectorClass>com.example.testorder.ManifestChangeDetector</changeDetectorClass>
        <changeDetectorArgs>
            <arg>/path/to/manifest.txt</arg>
        </changeDetectorArgs>
    </configuration>
</plugin>
```

---

## 2. Custom Test Ranker

### Interface

```java
package me.bechberger.testorder.ranking;

public interface TestRanker {
    /**
     * Scores a test class against the set of changed classes.
     * Higher score = higher priority (runs earlier).
     * @param testClassName  FQCN of the test class
     * @param testDeps       Classes exercised by this test
     * @param changedClasses Classes that changed
     * @return score ≥ 0; higher is more important
     */
    double score(String testClassName,
                 Set<String> testDeps,
                 Set<String> changedClasses);
}
```

### Default Scoring Algorithm

The built-in ranker uses Jaccard-like overlap:

```java
public class DefaultTestRanker implements TestRanker {
    @Override
    public double score(String testClass, Set<String> deps, Set<String> changed) {
        if (changed.isEmpty() || deps.isEmpty()) return 0.0;
        long overlap = deps.stream().filter(changed::contains).count();
        // Normalize by the smaller set to reward focused tests
        return (double) overlap / Math.min(deps.size(), changed.size());
    }
}
```

### Example: Prioritize Recently-Failing Tests

```java
public class HistoryAwareRanker implements TestRanker {

    // Map from test FQCN → consecutive failure count
    private final Map<String, Integer> failureHistory;

    public HistoryAwareRanker(Map<String, Integer> failureHistory) {
        this.failureHistory = failureHistory;
    }

    @Override
    public double score(String testClass, Set<String> deps, Set<String> changed) {
        double baseScore = new DefaultTestRanker().score(testClass, deps, changed);
        int failures = failureHistory.getOrDefault(testClass, 0);
        // Boost by 10% per consecutive failure
        return baseScore * (1.0 + failures * 0.10);
    }
}
```

### Registering

```xml
<configuration>
    <testRankerClass>com.example.testorder.HistoryAwareRanker</testRankerClass>
</configuration>
```

---

## 3. Custom Index Persistence

### Interface

```java
package me.bechberger.testorder.index;

public interface DependencyIndexPersistence {
    void write(DependencyIndex index, Path destination) throws IOException;
    DependencyIndex read(Path source) throws IOException;
}
```

### Example: JSON Persistence (human-readable debug format)

```java
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class JsonDependencyIndexPersistence implements DependencyIndexPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void write(DependencyIndex index, Path destination) throws IOException {
        String json = GSON.toJson(index.getTestDependencies());
        Files.writeString(destination, json);
    }

    @Override
    @SuppressWarnings("unchecked")
    public DependencyIndex read(Path source) throws IOException {
        String json = Files.readString(source);
        Map<String, List<String>> raw = GSON.fromJson(json,
            new com.google.gson.reflect.TypeToken<Map<String, List<String>>>(){}.getType());
        Map<String, Set<String>> deps = new HashMap<>();
        raw.forEach((test, list) -> deps.put(test, new HashSet<>(list)));
        return new DependencyIndex(deps);
    }
}
```

### Registering

```xml
<configuration>
    <indexPersistenceClass>com.example.testorder.JsonDependencyIndexPersistence</indexPersistenceClass>
    <indexFile>${project.basedir}/test-dependencies.json</indexFile>
</configuration>
```

---

## 4. Custom CI Downloader

### Interface

```java
package me.bechberger.testorder.cli;

public interface ArtifactDownloader {
    /**
     * Download the dependency index artifact.
     * @param config  CI configuration from .test-order.yml
     * @param destDir Directory to write the artifact into
     * @return Path to the downloaded file, or empty if not available
     */
    Optional<Path> download(CiConfig config, Path destDir) throws IOException;
}
```

### Example: S3 Downloader

```java
public class S3ArtifactDownloader implements ArtifactDownloader {

    @Override
    public Optional<Path> download(CiConfig config, Path destDir) throws IOException {
        String bucket = config.getExtra("s3-bucket");
        String key    = config.getExtra("s3-key");
        if (bucket == null || key == null) return Optional.empty();

        Path local = destDir.resolve("test-dependencies.lz4");
        try (S3Client s3 = S3Client.create()) {
            s3.getObject(r -> r.bucket(bucket).key(key),
                         ResponseTransformer.toFile(local));
        }
        return Optional.of(local);
    }
}
```

### CI Config (`.test-order.yml`)

```yaml
ci:
  type: s3
  downloader: com.example.testorder.S3ArtifactDownloader
  extra:
    s3-bucket: my-ci-artifacts
    s3-key: test-dependencies/main/test-dependencies.lz4
```

---

## 5. Custom Coverage Reporter

### Interface

```java
package me.bechberger.testorder.coverage;

public interface CoverageOutputFormat {
    /** File extension for this format (e.g. "html", "csv"). */
    String extension();

    /** Write the coverage report to the given path. */
    void write(CoverageReport report, Path output) throws IOException;
}
```

### Example: HTML Report

```java
public class HtmlCoverageFormat implements CoverageOutputFormat {

    @Override
    public String extension() { return "html"; }

    @Override
    public void write(CoverageReport report, Path output) throws IOException {
        StringBuilder html = new StringBuilder("""
            <!DOCTYPE html>
            <html><head><title>Coverage Report</title></head>
            <body><h1>Least Tested Classes</h1><table>
            <tr><th>Class</th><th>Coverage</th><th>Tests</th></tr>
            """);
        for (ClassMetrics m : report.getLeastTestedClasses()) {
            html.append(String.format(
                "<tr><td>%s</td><td>%.0f%%</td><td>%d</td></tr>%n",
                m.getClassName(), m.getCoverage() * 100, m.getTestCount()));
        }
        html.append("</table></body></html>");
        Files.writeString(output, html.toString());
    }
}
```

### Registering

```xml
<configuration>
    <outputFormat>html</outputFormat>
    <customFormats>
        <format>com.example.testorder.HtmlCoverageFormat</format>
    </customFormats>
</configuration>
```

---

## Module Dependency Map for Extension Authors

```
test-order-parent
    └─ test-order-core         ← Change detection, index, ranking interfaces live here
        ├─ test-order-agent     ← Bytecode instrumentation (extend ClassTransformer)
        ├─ test-order-junit     ← JUnit ClassOrderer (extend TestClassOrderer)
        ├─ test-order-maven-plugin  ← Maven MojoContext, AbstractTestOrderMojo
        ├─ test-order-gradle-plugin ← Gradle TestOrderExtension
        ├─ test-order-cli       ← CiConfig, ArtifactDownloader, ArtifactCache
        └─ test-order-coverage-mojo ← CoverageOutputFormat, CoverageReporter
```

### Adding test-order-core as a dependency

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

---

## Testing Your Extension

### Unit Test Skeleton

```java
class ManifestChangeDetectorTest {

    @TempDir Path tmp;

    @Test
    void parsesManifestLines() throws Exception {
        Path manifest = tmp.resolve("changes.txt");
        Files.writeString(manifest, """
            com.example.PaymentService
            # ignore this comment
            com.example.OrderProcessor
            """);

        var detector = new ManifestChangeDetector(manifest);
        var ctx = FakeChangeDetectionContext.of(tmp);

        Set<String> changed = detector.getChangedClasses(ctx);

        assertEquals(Set.of("com.example.PaymentService",
                             "com.example.OrderProcessor"), changed);
    }

    @Test
    void returnsEmptyWhenManifestMissing() throws Exception {
        var detector = new ManifestChangeDetector(tmp.resolve("nonexistent.txt"));
        var ctx = FakeChangeDetectionContext.of(tmp);

        assertTrue(detector.getChangedClasses(ctx).isEmpty());
    }
}
```

### Integration Test with a Real Maven Project

```java
class ManifestIntegrationTest extends AbstractMavenIntegrationTest {

    @Test
    void selectiveTestRunUsesManifest(@TempDir Path project) throws Exception {
        setupMavenProject(project, "sample-basic");

        Path manifest = project.resolve("changes.txt");
        Files.writeString(manifest, "com.example.CalculatorService\n");

        MavenResult result = runMaven(project,
            "test-order:combined", "test",
            "-DchangeDetectorClass=com.example.ManifestChangeDetector",
            "-DchangeDetectorArgs=" + manifest);

        assertSuccess(result);
        assertTestRan(result, "CalculatorServiceTest");
        assertTestSkipped(result, "UnrelatedTest");
    }
}
```

---

## Contribution Guidelines

1. **Follow the existing package structure** – extensions in `me.bechberger.testorder.<module>`
2. **Add unit tests** – every public class needs tests
3. **JavaDoc all public APIs** – especially interfaces and their parameters
4. **Keep extensions stateless where possible** – easier to test and reason about
5. **Prefer composition over inheritance** – decorate `DefaultTestRanker` instead of subclassing
6. **Use `@Parameter` for Maven config** – so users can set values in `pom.xml` or CLI

---

## Useful Test Utilities

| Class | Purpose |
|-------|---------|
| `FakeChangeDetectionContext` | Stub for unit-testing change detectors |
| `InMemoryDependencyIndex` | In-memory index for tests, no disk I/O |
| `TestMetricsBuilder` | Fluent builder for `ClassMetrics` fixtures |
| `MockWebServer` (OkHttp3) | Fake HTTP server for CI downloader tests |
| `AbstractMavenIntegrationTest` | Base class for full Maven plugin integration tests |
