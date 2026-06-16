# test-order-junit

JUnit 5 extension for test-order. Provides auto-discovered extensions for test class ordering, method ordering, state persistence, and TDD enforcement.

| Class | JUnit 5 SPI | Role |
|---|---|---|
| `PriorityClassOrderer` | `ClassOrderer` | Reorders test classes before execution |
| `PriorityMethodOrderer` | `MethodOrderer` | Reorders test methods within a class |
| `TelemetryListener` | `TestExecutionListener` | Records durations, failures, and learn-mode boundaries |
| `TddEnforcementExtension` | `AfterTestExecutionCallback` | Fails new tests that pass without having failed first |
| `FixedOrderClassOrderer` | `ClassOrderer` | Applies a fixed order from a file |
| `FixedOrderMethodOrderer` | `MethodOrderer` | Applies a fixed method order from a file |

All classes are registered via `META-INF/services/` and loaded automatically through JUnit's service loader — no annotation changes or `junit-platform.properties` modifications required.

## How it works

### Learn mode

When `testorder.learn=true` is set (via the Maven/Gradle plugin), `TelemetryListener` communicates class and method boundaries to the agent's `UsageStore` via reflection. The agent records which production classes each test class touches, producing `.deps` files later aggregated into the dependency index.

For **offline instrumentation** (the default), the listener also bootstraps `OfflineRuntimeBootstrap` when `testorder.offline.mapping` is set, loading the class-id mapping produced during the instrument phase.

In `METHOD` and `MEMBER` instrumentation modes, per-method dependency tracking is also enabled: the listener calls `startTestMethod`/`endTestMethod` around each `@Test` invocation, including special handling for `@ParameterizedTest` containers (tracking starts on the container node to capture `@MethodSource` provider calls before individual invocations fire).

### Order mode

`PriorityClassOrderer.orderClasses()` is called once before any test runs. It:

1. Loads the dependency index and state file via `TestOrderConfigResolver`
2. Scores each class with `TestScorer` (dep-overlap, failure history, novelty, change score)
3. Pins `@AlwaysRun` and `@TestOrder(priority=FIRST)` classes first; `@TestOrder(priority=LAST)` last; `@Order`-annotated classes after pins but before score-sorted classes
4. Groups remaining classes by score descending, then applies Jaccard-diversity tie-breaking within each score bucket
5. Optionally groups Spring context tests to minimize context restarts (`testorder.springContextGrouping=true`)
6. Injects the state into `PriorityMethodOrderer` for method-level ordering if `testorder.methodOrder.enabled=true`

`PriorityMethodOrderer.orderMethods()` is called once per test class. It:

1. Skips classes with `@Execution(CONCURRENT)` (method ordering is ineffective in parallel)
2. Skips classes with `@TestInstance(PER_CLASS)` (reordering may break stateful tests)
3. Defers to any other `@TestMethodOrder` orderer the class declares
4. Scores each method using `MethodScorer` (failure recency, speed vs class median, dep overlap, changed-method bonus)
5. Applies `@TestOrder` and `@AlwaysRun` annotation overrides

`TelemetryListener` records class and method durations and test failures after each test completes. At the end of the test plan, it writes them to the state file under a file lock. When build-session aggregation is active (`testorder.build.id` set), it writes a partial run record to `pending-runs/` instead of directly to the state file; the Maven plugin merges all per-fork records after all forks finish.

### State persistence

The listener registers a JVM shutdown hook on `testPlanExecutionStarted`. If the JVM terminates abnormally (OOM, SIGTERM) before `testPlanExecutionFinished` runs, the hook calls `emergencySave` to persist whatever durations and failures have accumulated. The `finishedNormally` flag prevents double-saves when normal completion and the shutdown hook race.

## Configuration

All properties are read via `TestOrderConfigResolver`, which checks system properties first, then `testorder-config.properties` on the classpath (written by the Maven/Gradle plugin during `prepare`).

| Property | Description |
|---|---|
| `testorder.index.path` | Path to the dependency index (`.lz4`) |
| `testorder.state.path` | Path to the state file (`.lz4`) |
| `testorder.changed.classes` | Comma-separated FQCNs of changed production classes |
| `testorder.changed.test.classes` | Comma-separated FQCNs of changed test classes |
| `testorder.learn` | `true` to enable learn mode |
| `testorder.instrumentation.mode` | `CLASS` (default), `METHOD`, or `MEMBER` |
| `testorder.methodOrder.enabled` | `true` to enable within-class method ordering |
| `testorder.offline.mapping` | Path to offline class-id mapping file |
| `testorder.springContextGrouping` | `true` to group Spring context tests together |
| `testorder.tdd` | `true` to enable TDD enforcement |
| `testorder.build.id` | Build session ID for parallel fork aggregation |
| `testorder.pending.runs.dir` | Directory for partial run records |
| `testorder.debug` | `true` to enable verbose per-class/method score logging |

## Maven setup

```xml
<dependencies>
    <dependency>
        <groupId>me.bechberger</groupId>
        <artifactId>test-order-junit</artifactId>
        <version>${test-order.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>me.bechberger</groupId>
            <artifactId>test-order-maven-plugin</artifactId>
            <version>${test-order.version}</version>
            <extensions>true</extensions>
            <executions>
                <execution>
                    <goals><goal>prepare</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Typical workflow:

```bash
# Learn: instrument + record dependency data
mvn test

# Order: run tests in priority order using the recorded index
mvn test

# Or force a specific mode
mvn test -Dtestorder.mode=learn     # always re-learn
mvn test -Dtestorder.mode=order     # require existing index
```

## @TestOrder and @AlwaysRun annotations

Both `PriorityClassOrderer` and `PriorityMethodOrderer` respect the annotations from `test-order-annotations`:

- `@AlwaysRun` — pin the class (or method) to run first, regardless of score
- `@TestOrder(priority=FIRST)` — same as `@AlwaysRun` with score adjustments
- `@TestOrder(priority=LAST)` — pin to the end
- `@TestOrder(priority=HIGH/LOW)` — shift score up or down by a fixed boost
- `@TestOrder(scoreBonus=N)` — add N directly to the computed score
- `@TestOrder(changeBonus=N)` — additional bonus when the item is in the changed set

JUnit's `@Order` annotation is also recognised on classes: `@Order`-annotated classes are sorted by their `@Order` value and placed after FIRST-pinned classes but before the score-sorted main group.

## TDD enforcement

When `testorder.tdd=true`, `TddEnforcementExtension` fails any test that passes on the first run if the state file has no prior record of it. This enforces red-green-refactor: you must see the test fail before it is allowed to pass.

- New test classes (not in state) trigger a class-level violation
- New test methods in an existing class trigger a method-level violation
- Renamed classes (same method set, different name) are detected and still enforced
- Renamed methods (same body hash, different name) are suppressed — the old hash entry counts as the "fail" record
- `@ParameterizedTest` fires only one violation per method per run

## Fixed-order orderers

`FixedOrderClassOrderer` and `FixedOrderMethodOrderer` apply a static ordering read from a plain-text file (one name per line, `#` lines ignored). Useful when a specific execution order is required for debugging or reproducibility. Configure via:

- `testorder.fixedOrder.file` — path to the class order file
- `testorder.fixedOrder.method.file` — path to the method order file

## Parallel execution

The JUnit extension is safe for JUnit's parallel test execution (`@Execution(CONCURRENT)`) with the following behaviour:

- `TelemetryListener` uses `ConcurrentHashMap` for all mutable state; `executionOrderSet.add()` deduplicates concurrent class-start callbacks
- `PriorityClassOrderer` is called once before execution, not during; ordering happens in a single thread
- `PriorityMethodOrderer` skips reordering when it detects `@Execution(CONCURRENT)` on the test class — method ordering is ineffective in parallel
- `PriorityMethodOrderer` skips reordering for `@TestInstance(PER_CLASS)` classes — method ordering may break stateful tests

## Dry-run and debug mode

- **Dry-run** (`junit.platform.execution.dryRun.enabled=true`): all telemetry recording is skipped; no state file is written.
- **Debug mode** (JVM started with `-agentlib:jdwp`): duration recording is automatically disabled. Breakpoint-inflated timings would corrupt EMA-based speed scores.

## Differences from the TestNG extension

See [test-order-testng/README.md](../test-order-testng/README.md) for the TestNG module. The table below summarises the key design differences:

| | JUnit 5 | TestNG |
|---|---|---|
| Ordering hook | Two separate SPIs: `ClassOrderer` (once, before all classes) + `MethodOrderer` (once per class) | Single `IMethodInterceptor.intercept()` call with all methods across all classes |
| Class boundary for learn | `executionStarted`/`executionFinished` events on `ClassSource` nodes | `onTestStart` / `onAfterClass` callbacks |
| Method boundary for learn | `executionStarted`/`executionFinished` events on `MethodSource` nodes; container-node tracking for `@ParameterizedTest` | `onTestStart` / `onTestSuccess`/`onTestFailure` per invocation |
| `@AlwaysRun` support | Yes (class and method level) | Yes (class level) |
| `@Order` support | Yes (JUnit's built-in; placed after FIRST-pinned, before scored) | No equivalent |
| Dependency constraints | N/A — JUnit has no `dependsOnMethods` | Enforced by a post-score fixup pass |
| `@TestInstance(PER_CLASS)` | Method ordering skipped (stateful tests) | N/A |
| `@Execution(CONCURRENT)` | Method ordering skipped (ineffective) | Handled via `ConcurrentHashMap` in telemetry |
| Dry-run detection | `junit.platform.execution.dryRun.enabled` | Not applicable |
| TDD enforcement | `TddEnforcementExtension` (AfterTestExecutionCallback) | Not implemented |
| Fixed-order orderers | `FixedOrderClassOrderer` / `FixedOrderMethodOrderer` | Not implemented |
| Spring context grouping | Supported (`testorder.springContextGrouping=true`) | Not implemented |
| State persistence | `TelemetryListener` (`TestExecutionListener`) | `TestNGTelemetryListener` (`ITestListener` + `IClassListener`) |
| Suite engine dedup | Filters `[engine:junit-platform-suite]` nodes to prevent double-counting | N/A |
