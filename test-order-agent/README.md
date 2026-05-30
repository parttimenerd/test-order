# test-order-agent

Java agent that records per-test-class dependency data via bytecode instrumentation.
The data feeds the ordering/scoring engine to prioritise tests covering recently-changed code.

## How it works

1. **Attach** — JAR attached as `-javaagent` before the test JVM starts.
2. **Bootstrap** — `Agent.premain` extracts `test-order-runtime.jar` into a temp file and
   adds it to the bootstrap classpath so `UsageStore` is visible to all classloaders.
3. **Instrument** — `ClassTransformer` (Javassist) injects `UsageStore.recordUsageIdFast(id)`
   at every method/constructor entry. `CLASS`/`METHOD`/`MEMBER` modes also track
   field accesses.
4. **Track** — `UsageStore` holds a volatile `ActiveTrackers` reference. Recording is a
   no-op until the active tracker is set.
5. **Flush** — on JVM shutdown, bitsets are converted to class-name sets and either merged
   directly into `test-dependencies.lz4` or written as `.deps` text files.

## ⚠️ Not usable standalone — requires a telemetry listener

The agent instruments bytecode but never starts/stops tracking by itself.
`UsageStore.startTestClass` / `endTestClass` must be called around each test class — that
is done by a framework-specific listener that detects `testorder.learn=true` and calls
those methods via reflection:

| Test framework | Listener class            | Module              | Discovery mechanism                                                   |
|----------------|---------------------------|---------------------|-----------------------------------------------------------------------|
| JUnit Platform | `TelemetryListener`       | `test-order-junit`  | `META-INF/services/org.junit.platform.launcher.TestExecutionListener` |
| TestNG         | `TestNGTelemetryListener` | `test-order-testng` | `META-INF/services/org.testng.ITestNGListener`                        |

Without the listener, no per-test boundaries are set, `flush()` records nothing, and a
warning is printed to stderr. The Maven and Gradle plugins inject the correct listener JAR
onto the test classpath automatically. **Manual agent attachment requires adding the
appropriate listener JAR to the test classpath yourself.**

## Instrumentation modes

| Mode               | Field tracking                      | Overhead |
|--------------------|-------------------------------------|----------|
| `CLASS` *(default)* | Static only                         | ~66%     |
| `METHOD`           | Static only + per-method deps       | ~68%     |
| `MEMBER`           | Instance + static + per-method deps | ~121%    |

## Agent arguments

```
-javaagent:/path/to/test-order-agent.jar=outputDir=target/test-order-deps,mode=CLASS,indexFile=test-dependencies.lz4,includePackages=com.example;org.app
```

| Option                 | Default                  | Description                                          |
|------------------------|--------------------------|------------------------------------------------------|
| `--outputDir`          | `target/test-order-deps` | Directory for `.deps` fallback files                 |
| `--indexFile`          | `test-dependencies.lz4`  | Binary index for direct merge                        |
| `--mode`               | `CLASS`                  | Instrumentation mode                                 |
| `--includePackages`    | *(auto-detect)*          | Semicolon-separated package prefixes to instrument   |
| `--excludePackages`    | *(none)*                 | Semicolon-separated prefixes to skip                 |
| `--autoDetectPackages` | `true`                   | Detect user packages from `pom.xml` / `build.gradle` |
| `--verboseFile`        | *(disabled)*             | Path to verbose log file                             |

## Design notes

* **Concurrent test classes in one fork** — `UsageStore` uses a single volatile
  `activeTrackers`; concurrent test classes would cross-contaminate. Not an issue because
  Surefire/Gradle fork one JVM per class (or run sequentially).
* **Regex build-file parsing** — `ProjectStructureAnalyzer` uses regex on `pom.xml` /
  `build.gradle` for package auto-detection; may misparse unusual files.