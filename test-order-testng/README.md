# test-order-testng

TestNG extension for test-order. Provides two auto-discovered listeners:

| Class | TestNG SPI | Role |
|---|---|---|
| `TestNGPriorityInterceptor` | `IMethodInterceptor` | Reorders all test methods across all classes before execution |
| `TestNGTelemetryListener` | `ITestListener` + `IClassListener` | Records durations, failures, and learn-mode dependency boundaries |

Both are registered via `META-INF/services/org.testng.ITestNGListener` and loaded automatically by TestNG's service loader — no `testng.xml` changes required.

## How it works

### Learn mode

When `testorder.learn=true` is set (via the Maven/Gradle plugin), `TestNGTelemetryListener` communicates class and method boundaries to the agent's `UsageStore` via reflection. The agent records which production classes each test class touches, producing `.deps` files that are later aggregated into the dependency index.

For **offline instrumentation** (the default), the listener also bootstraps the `OfflineRuntimeBootstrap` when `testorder.offline.mapping` is set, loading the class-id mapping produced during the instrument phase.

### Order/auto mode

`TestNGPriorityInterceptor.intercept()` receives all methods across all classes in a single call. It:

1. Groups methods by declaring class
2. Scores each class with `TestScorer` (dep-overlap, failure history, novelty, change score)
3. Pins `@AlwaysRun` classes first, then sorts remaining classes by score descending with Jaccard-diversity tie-breaking
4. Reorders methods within each class via `MethodOrderingEngine` when `testorder.methodOrder.enabled=true`
5. Enforces `@Test(dependsOnMethods=…)` and `@Test(dependsOnGroups=…)` constraints after scoring

`TestNGTelemetryListener` records class durations and failures into the state file after each run. Duration is computed as sum-of-method-durations (accurate under `parallel="methods"`) with a wall-clock fallback for classes without method data.

### Parallelism

Both listeners are safe for `parallel="methods"` and `parallel="classes"`:

- `TestNGTelemetryListener.onTestStart` uses `ConcurrentHashMap.putIfAbsent` to ensure exactly one thread calls `startTestClass` per class (prevents the add-then-call race).
- `TestNGPriorityInterceptor` is stateless after construction — `config` is initialized eagerly in the constructor rather than lazily, so concurrent `intercept()` calls need no synchronization.

## Configuration

Same system properties as the JUnit extension:

| Property | Description |
|---|---|
| `testorder.index.path` | Path to the dependency index (`.lz4`) |
| `testorder.state.path` | Path to the state file (`.lz4`) |
| `testorder.changed.classes` | Comma-separated FQCNs of changed classes |
| `testorder.learn` | `true` to enable learn mode |
| `testorder.instrumentation.mode` | `CLASS` (default), `METHOD`, or `MEMBER` |
| `testorder.methodOrder.enabled` | `true` to enable within-class method ordering |
| `testorder.offline.mapping` | Path to offline class-id mapping file |

All properties can also be set via `testorder-config.properties` on the classpath.

## Maven setup

```xml
<dependencies>
    <dependency>
        <groupId>me.bechberger</groupId>
        <artifactId>test-order-testng</artifactId>
        <version>${test-order.version}</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testng</groupId>
        <artifactId>testng</artifactId>
        <version>7.10.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>test-order-maven-plugin</artifactId>
            <version>${test-order.version}</version>
        </plugin>
    </plugins>
</build>
```

Typical workflow (same as JUnit):

```bash
# Learn: instrument + record dependency data
mvn test-order:prepare test

# Order: run tests in priority order using the recorded index
mvn test-order:auto test

# Or combined auto mode (learns on first run, orders on subsequent runs)
mvn test -Dtestorder.mode=auto
```

## DataProvider support

When a test method is backed by a `@DataProvider`, TestNG passes multiple `IMethodInstance` entries for the same method name. The interceptor groups them under a single score and expands the group into consecutive slots in the final order — DataProvider repetitions always stay together.

## Dependency constraint enforcement

`@Test(dependsOnMethods={"setup"})` constraints are respected after scoring: if the scorer places a dependent method before its dependency, a fixup pass moves it to just after the latest dependency. The pass repeats until the order is stable (at most N iterations for N methods), and the position map is updated incrementally rather than rebuilt from scratch on each iteration.

## Debug mode

When the JVM is started with `-agentlib:jdwp` (debugger attached), duration recording is automatically disabled. This prevents breakpoint-inflated timings from corrupting EMA-based speed scores.

## Differences from the JUnit 5 extension

| | JUnit 5 | TestNG |
|---|---|---|
| Ordering hook | `ClassOrderer` + `MethodOrderer` (separate) | `IMethodInterceptor` (all methods, all classes, single call) |
| Class boundary for learn | `@BeforeEach`/`@AfterEach` of each test class | `onTestStart` / `onAfterClass` |
| `@AlwaysRun` support | Yes | Yes |
| `@Test(dependsOnMethods)` | N/A (JUnit has no equivalent) | Enforced by post-score fixup |
| Parallel execution | Thread-safe via `ExecutionContext` | Thread-safe via `ConcurrentHashMap` |
| State persistence | `TelemetryListener` (`ITestExecutionListener`) | `TestNGTelemetryListener` (`ITestListener`) |
