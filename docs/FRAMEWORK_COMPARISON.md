# JUnit 5 vs TestNG: Framework Comparison

test-order supports JUnit 5/6 (plus Vintage for JUnit 4 migration) and TestNG 7.x+. The core ordering and telemetry logic is shared, but the two framework integrations differ in their extension points, capabilities, and limitations.

For full details on each:
- [test-order-junit/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-junit/README.md)
- [test-order-testng/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-testng/README.md)

---

## At a glance

| Capability | JUnit 5 | TestNG |
|---|---|---|
| Dependency | `test-order-junit` | `test-order-testng` |
| Test class ordering | `PriorityClassOrderer` (`ClassOrderer`) | `TestNGPriorityInterceptor` (`IMethodInterceptor`) |
| Test method ordering | `PriorityMethodOrderer` (`MethodOrderer`) | `TestNGPriorityInterceptor` (same call) |
| Telemetry | `TelemetryListener` (`TestExecutionListener`) | `TestNGTelemetryListener` (`ITestListener` + `IClassListener`) |
| TDD enforcement | `TddEnforcementExtension` | Not implemented |
| Fixed-order orderers | Yes (`FixedOrderClassOrderer/MethodOrderer`) | Not implemented |
| Spring context grouping | `testorder.springContextGrouping=true` | Not implemented |
| `@TestOrder` / `@AlwaysRun` | Class and method level | Class level only |
| JUnit `@Order` support | Yes | No equivalent |
| `dependsOnMethods` constraints | N/A | Enforced by post-score fixup |
| Vintage (JUnit 4) | Via Vintage engine | N/A |
| Kotest | Via JUnit Platform runner | N/A |
| Auto-discovery | `META-INF/services/` (JUnit Platform) | `META-INF/services/` (TestNG) |

---

## Architecture differences

### Ordering hook granularity

**JUnit 5** exposes ordering through two separate SPIs:

- `ClassOrderer.orderClasses()` — called once before any test class runs, with the full list of classes.
- `MethodOrderer.orderMethods()` — called once per test class, independently for each class.

State is passed from `PriorityClassOrderer` to `PriorityMethodOrderer` via a static `setPendingState()` call before test execution starts. This design matches how JUnit Platform loads and invokes orderers.

**TestNG** provides a single `IMethodInterceptor.intercept()` call that receives all test methods across all test classes in one shot. The interceptor handles both class and method ordering in one pass: it groups methods by declaring class, scores classes, then (optionally) re-orders methods within each class group. This means class and method ordering share a single code path without the cross-class state injection needed in JUnit.

### Learn mode boundaries

**JUnit 5** uses `TestExecutionListener.executionStarted` and `executionFinished` events on `ClassSource` nodes to detect class boundaries. For method-level tracking (`METHOD`/`MEMBER` modes), it tracks `MethodSource` nodes, with special container-node handling for `@ParameterizedTest` (tracking starts on the template container, not the individual invocations, to capture `@MethodSource` provider execution before arguments are resolved).

**TestNG** uses `IClassListener.onAfterClass` and `ITestListener.onTestStart` for class boundaries. Because TestNG calls `onTestStart` once per test method invocation, the first call for a given class signals the class start. `ConcurrentHashMap.putIfAbsent` ensures exactly one thread fires `startTestClass` when tests run in parallel.

### Duration accounting

**JUnit 5** measures class duration from the `executionStarted` event on the `ClassSource` node to its `executionFinished` event — this is wall-clock time for the entire class including `@BeforeEach`/`@AfterEach` and any nested class overhead.

**TestNG** computes class duration as the sum of individual method durations accumulated in `onTestSuccess`/`onTestFailure`/`onTestSkipped`, with a wall-clock fallback (from `IClassListener.onBeforeClass` to `onAfterClass`) when no method durations are recorded. The sum-of-methods approach is more accurate under `parallel="methods"` because the wall-clock would include idle waiting time.

---

## Feature gap: JUnit-only capabilities

### TDD enforcement

`TddEnforcementExtension` is JUnit 5-only. It hooks into `AfterTestExecutionCallback` to detect tests that pass without a prior failure record in the state file. There is no equivalent in the TestNG module.

### Fixed-order orderers

`FixedOrderClassOrderer` and `FixedOrderMethodOrderer` read a plain-text file of class/method names and apply that order deterministically. Useful for debugging or reproducing a specific execution order. TestNG has no equivalent in this module.

### Spring context grouping

`testorder.springContextGrouping=true` groups Spring `@ContextConfiguration`-annotated test classes by their context key, minimising the number of Spring context restarts. This optimisation is JUnit-only.

### Method-level ordering guards

JUnit's method orderer detects and respects two constraints that have no TestNG equivalent:

- `@Execution(CONCURRENT)` — method ordering is skipped because it is ineffective when methods run in parallel
- `@TestInstance(PER_CLASS)` — method ordering is skipped because test instances share state across methods, and reordering may break tests that rely on execution order

### `@Order` annotation interop

JUnit's built-in `@Order` annotation is recognised by `PriorityClassOrderer`: `@Order`-annotated classes are sorted by their value and placed after FIRST-pinned classes but before the score-sorted main group. TestNG has no built-in ordering annotation equivalent.

---

## Feature gap: TestNG-only capabilities

### `dependsOnMethods` and `dependsOnGroups`

TestNG's `@Test(dependsOnMethods={"setup"}, dependsOnGroups={"init"})` constraints are enforced by `TestNGPriorityInterceptor` after scoring: if the scorer places a dependent method before its dependency, a fixup pass moves it to just after the latest dependency. JUnit has no equivalent test-dependency declaration mechanism.

### DataProvider grouping

When a test method has a `@DataProvider`, TestNG presents multiple `IMethodInstance` entries for the same method name. The interceptor groups them under a single score and expands the group into consecutive slots — DataProvider repetitions always stay together. JUnit's `@ParameterizedTest` does not require this treatment because JUnit's framework handles parameterized test ordering separately.

---

## Migration: switching frameworks

If you are evaluating moving a TestNG project to JUnit 5 and want to preserve test-order behaviour, the main differences to handle:

1. **`dependsOnMethods`** — convert to `@TestMethodOrder` + `@Order` on the methods, or restructure tests to be independent.
2. **Class-level `@AlwaysRun`** — maps directly to `@AlwaysRun` on the JUnit class.
3. **Method-level `@AlwaysRun`** — also works in JUnit; `PriorityMethodOrderer` respects it.
4. **No `IMethodInterceptor` equivalent** — JUnit orderers are separate for class and method level; there is no single interception point for all methods across all classes.

---

## Shared configuration

All properties in [CLI_REFERENCE.mdx](CLI_REFERENCE.mdx) apply to both frameworks unless noted. Both modules read configuration from system properties and from `testorder-config.properties` on the classpath (written by the Maven/Gradle plugin).

The key properties with framework-specific behaviour:

| Property | JUnit 5 | TestNG |
|---|---|---|
| `testorder.methodOrder.enabled` | Activates `PriorityMethodOrderer` | Activates method ordering inside `TestNGPriorityInterceptor` |
| `testorder.springContextGrouping` | Groups by Spring context key | No effect |
| `testorder.tdd` | Activates `TddEnforcementExtension` | No effect |
| `testorder.fixedOrder.file` | Read by `FixedOrderClassOrderer` | No effect |
| `testorder.fixedOrder.method.file` | Read by `FixedOrderMethodOrderer` | No effect |
