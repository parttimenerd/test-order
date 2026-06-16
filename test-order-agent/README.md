# test-order-agent

Contains two distinct but related things:

1. **The instrumentation logic** — `OfflineInstrumentor` and `AsmClassTransformer` transform
   bytecode to inject `UsageStore` recording calls.
2. **The runtime recording layer** — `UsageStore`, `BitsetTracker`, `ClassIdMap`, and friends
   that live on the bootstrap classpath and actually record which classes each test touches.

## Default mode: offline instrumentation (no `-javaagent`)

Both the Maven and Gradle plugins default to `instrumentation=offline`. In this mode:

1. The plugin calls `OfflineInstrumentor` **at build time**, transforming compiled class files
   in place and writing a `class-id-map.bin` mapping file.
2. The plugin adds `test-order-runtime.jar` to the test classpath (not the bootstrap classpath).
3. At test startup `OfflineRuntimeBootstrap` loads `class-id-map.bin` and configures
   `UsageStore` — no `-javaagent` flag is needed.
4. After tests finish the plugin restores the original class files from a backup.

The agent JAR is still required as a dependency because it contains both `OfflineInstrumentor`
(run by the plugin process) and `test-order-runtime.jar` (run inside the test JVM). The
`premain` entry point is **not** used in this path.

## Online mode: Java agent (`instrumentation=online`)

Set `testorder.instrumentation=online` (or `instrumentation=online` in Gradle config) to
attach the agent instead. In this mode:

1. `Agent.premain` extracts `test-order-runtime.jar` into a temp file and appends it to the
   **bootstrap classpath** so `UsageStore` is visible to all classloaders.
2. `AsmClassTransformer` is registered as a `ClassFileTransformer` and instruments classes
   as they are loaded.
3. Flush and collection work identically to offline mode.

Online mode is useful when build-time instrumentation is impractical (e.g. incremental builds
that don't recompile all classes) but has higher per-fork startup overhead.

## How recording works (both modes)

1. **Instrument** — `AsmClassTransformer` (ASM streaming visitor) injects
   `UsageStore.recordClassOnly` / `recordMemberUsageIdFast` at every method/constructor entry
   and before static field accesses on foreign classes.
2. **Track** — `UsageStore` holds volatile `activeClassTracker` / `activeState` references.
   Recording is a no-op until the telemetry listener calls `startTestClass`.
3. **Flush** — on JVM shutdown, bitsets are converted to class-name sets and either sent over
   a local TCP socket to `IndexCollectorServer` (binary v3 protocol) or written as `.deps`
   text files as a fallback.

## ⚠️ Requires a telemetry listener

The agent/runtime instruments bytecode but never starts/stops tracking by itself.
`UsageStore.startTestClass` / `endTestClass` must be called around each test class — done by
a framework-specific listener that detects `testorder.learn=true` and calls those methods via
reflection:

| Test framework | Listener class            | Module              | Discovery mechanism                                                   |
|----------------|---------------------------|---------------------|-----------------------------------------------------------------------|
| JUnit Platform | `TelemetryListener`       | `test-order-junit`  | `META-INF/services/org.junit.platform.launcher.TestExecutionListener` |
| TestNG         | `TestNGTelemetryListener` | `test-order-testng` | `META-INF/services/org.testng.ITestNGListener`                        |

The Maven and Gradle plugins inject the correct listener JAR automatically.

## Instrumentation modes

| Mode               | What is recorded                                        | Overhead |
|--------------------|---------------------------------------------------------|----------|
| `CLASS` *(default)* | Class usage per test class; static field accesses      | ~66%     |
| `METHOD`           | Above + per-test-method class deps                     | ~68%     |
| `MEMBER`           | Above + member-level deps (class#method, class#field)  | ~121%    |

In all modes only **static** field accesses to foreign classes are tracked; instance field
accesses are already covered by method-entry recording of the accessor.

## Selective learn mode

When `testorder.learn.selective=true` is set, only classes listed in the file pointed to by
`testorder.learn.uncertainClassesFile` are instrumented. This reduces overhead for incremental
re-learns where most class mappings are already stable.

## Agent arguments (online mode only)

```
-javaagent:/path/to/test-order-agent.jar=outputDir=target/test-order-deps,mode=CLASS,indexFile=test-dependencies.lz4,includePackages=com.example;org.app
```

Both `key=value` and `--key=value` formats are accepted.

| Option                 | Default                  | Description                                          |
|------------------------|--------------------------|------------------------------------------------------|
| `outputDir`            | `target/test-order-deps` | Directory for `.deps` fallback files                 |
| `indexFile`            | `test-dependencies.lz4`  | Binary index for direct merge                        |
| `mode`                 | `CLASS`                  | Instrumentation mode (`CLASS`, `METHOD`, `MEMBER`)   |
| `includePackages`      | *(auto-detect)*          | Semicolon-separated package prefixes to instrument   |
| `excludePackages`      | *(none)*                 | Semicolon-separated prefixes to skip                 |
| `filterStrategy`       | `SMART`                  | Filter strategy (`SMART`, `WHITELIST`, `BLACKLIST`, `WHITELIST_SMART`) |
| `autoDetectPackages`   | `true`                   | Detect user packages from `pom.xml` / `build.gradle` |
| `projectRoot`          | *(working directory)*    | Root for auto-detection and relative paths           |
| `runtimeJarPath`       | *(embedded)*             | Pre-extracted runtime JAR path (skips temp-file extraction) |
| `verboseFile`          | *(disabled)*             | Path to verbose log file                             |

## Runtime classes

These classes live in `test-order-runtime.jar`, which is placed on the test classpath (offline
mode) or bootstrap classpath (online mode):

| Class                     | Role                                                          |
|---------------------------|---------------------------------------------------------------|
| `UsageStore`              | Singleton; receives recording calls from instrumented code    |
| `BitsetTracker`           | Lock-free bitset per test class / test method                 |
| `ClassIdMap`              | Maps FQCNs and member names to compact integer IDs            |
| `ClassIdMapping`          | Serialisation format for offline class-id mapping files       |
| `IndexCollectorClient`    | Sends flush data to `IndexCollectorServer` via TCP socket      |
| `OfflineRuntimeBootstrap` | Initialises `UsageStore` from an offline mapping file         |
| `AgentLogger`             | Lightweight logger; writes to stderr or a verbose file        |

## Design notes

* **Sequential test assumption** — `UsageStore` uses plain (non-volatile, non-ThreadLocal)
  fields for the active tracker because Surefire/Gradle run test classes sequentially on one
  thread per fork. Concurrent execution would require per-thread tracking.
* **Monotone bitsets** — bits are only ever set, never cleared during a run. This allows the
  speculative pre-check in `BitsetTracker` to use a plain read before the `lock or` atomic,
  eliminating the atomic instruction on the common "already set" path.
* **Cache key for extracted runtime JAR** — uses the agent JAR's size + mtime as a fast
  cache key so parallel test forks reuse the same extracted file without re-hashing it.
* **Regex build-file parsing** — `ProjectStructureAnalyzer` uses regex on `pom.xml` /
  `build.gradle` for package auto-detection; may misparse unusual files.


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

| Mode               | What is recorded                                        | Overhead |
|--------------------|---------------------------------------------------------|----------|
| `CLASS` *(default)* | Class usage per test class; static field accesses      | ~66%     |
| `METHOD`           | Above + per-test-method class deps                     | ~68%     |
| `MEMBER`           | Above + member-level deps (class#method, class#field)  | ~121%    |

In all modes only **static** field accesses are tracked; instance field accesses are
already covered by method-entry recording of the accessor.

## Selective learn mode

When `testorder.learn.selective=true` is set, only classes listed in the file pointed to
by `testorder.learn.uncertainClassesFile` are instrumented. This reduces overhead for
incremental re-learns where most class mappings are already stable.

## Agent arguments

```
-javaagent:/path/to/test-order-agent.jar=outputDir=target/test-order-deps,mode=CLASS,indexFile=test-dependencies.lz4,includePackages=com.example;org.app
```

Both `key=value` and `--key=value` formats are accepted.

| Option                 | Default                  | Description                                          |
|------------------------|--------------------------|------------------------------------------------------|
| `outputDir`            | `target/test-order-deps` | Directory for `.deps` fallback files                 |
| `indexFile`            | `test-dependencies.lz4`  | Binary index for direct merge                        |
| `mode`                 | `CLASS`                  | Instrumentation mode (`CLASS`, `METHOD`, `MEMBER`)   |
| `includePackages`      | *(auto-detect)*          | Semicolon-separated package prefixes to instrument   |
| `excludePackages`      | *(none)*                 | Semicolon-separated prefixes to skip                 |
| `filterStrategy`       | `SMART`                  | Filter strategy (`SMART`, `WHITELIST`, `BLACKLIST`, `WHITELIST_SMART`) |
| `autoDetectPackages`   | `true`                   | Detect user packages from `pom.xml` / `build.gradle` |
| `projectRoot`          | *(working directory)*    | Root for auto-detection and relative paths           |
| `runtimeJarPath`       | *(embedded)*             | Pre-extracted runtime JAR path (skips temp-file extraction) |
| `verboseFile`          | *(disabled)*             | Path to verbose log file                             |

## Runtime classes (bootstrap classpath)

These classes live in `test-order-runtime.jar` and are placed on the **bootstrap classpath**
so they are visible to every classloader, including classes loaded before the agent:

| Class                   | Role                                                          |
|-------------------------|---------------------------------------------------------------|
| `UsageStore`            | Singleton; receives recording calls from instrumented code    |
| `BitsetTracker`         | Lock-free bitset per test class / test method                 |
| `ClassIdMap`            | Maps FQCNs and member names to compact integer IDs            |
| `ClassIdMapping`        | Serialisation format for offline class-id mapping files       |
| `IndexCollectorClient`  | Sends flush data to `IndexCollectorServer` via TCP socket      |
| `OfflineRuntimeBootstrap` | Initialises `UsageStore` from an offline mapping file       |
| `AgentLogger`           | Lightweight logger; writes to stderr or a verbose file        |

## Design notes

* **Sequential test assumption** — `UsageStore` uses plain (non-volatile, non-ThreadLocal)
  fields for the active tracker because Surefire/Gradle run test classes sequentially on one
  thread per fork. Concurrent execution would require per-thread tracking.
* **Monotone bitsets** — bits are only ever set, never cleared during a run. This allows the
  speculative pre-check in `BitsetTracker` to use a plain read before the `lock or` atomic,
  eliminating the atomic instruction on the common "already set" path.
* **Cache key for extracted runtime JAR** — uses the agent JAR's size + mtime as a fast
  cache key so parallel test forks reuse the same extracted file without re-hashing it.
* **Regex build-file parsing** — `ProjectStructureAnalyzer` uses regex on `pom.xml` /
  `build.gradle` for package auto-detection; may misparse unusual files.
