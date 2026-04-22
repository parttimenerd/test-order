# TestNG Support

test-order supports TestNG 7.x+ projects with the same learn/order workflow as JUnit.

## How it works

The `test-order-testng` module provides two TestNG listeners, auto-discovered via `META-INF/services/org.testng.ITestNGListener`:

- **`TestNGTelemetryListener`** (`ITestListener`) — learn mode. Tracks which application classes each test exercises, records durations and failures, and persists the dependency index.
- **`TestNGPriorityInterceptor`** (`IMethodInterceptor`) — order mode. Receives all test methods across all classes in a single list, scores each class using the same `TestScorer` engine as JUnit, and reorders them so the most relevant tests run first.

Unlike JUnit 5 (which has separate `ClassOrderer` and `MethodOrderer` extension points), TestNG's `IMethodInterceptor` handles both class-level and method-level ordering in one pass.

## Setup

### Maven

No extra configuration is needed. The `test-order-maven-plugin` automatically detects TestNG on the test classpath and adds `test-order-testng` as a test dependency:

```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</plugin>
```

Then use the same workflow:

```bash
# Learn dependencies
mvn test-order:combined test

# Subsequent runs reorder automatically
mvn test-order:combined test
```

### Gradle

The Gradle plugin also auto-detects TestNG via `project.afterEvaluate` and adds the `test-order-testng` module as a `testRuntimeOnly` dependency.

## Configuration

All the same system properties work for TestNG:

- `testorder.index.path` — path to the dependency index
- `testorder.state.path` — path to the state file  
- `testorder.changed.classes` — comma-separated changed class FQCNs
- `testorder.score.*` — individual weight overrides
- `testorder.methodOrder.enabled` — enable method-level reordering within classes
- `testorder.debug` — enable debug logging

The `testorder-config.properties` classpath resource is also supported.

## Differences from JUnit

| Aspect | JUnit 5/6 | TestNG |
|--------|-----------|--------|
| Class ordering | `PriorityClassOrderer` (ClassOrderer SPI) | `TestNGPriorityInterceptor` (IMethodInterceptor) |
| Method ordering | `PriorityMethodOrderer` (MethodOrderer SPI) | Same interceptor, second pass |
| Auto-discovery | JUnit Platform service files | `META-INF/services/org.testng.ITestNGListener` |
| DataProvider | N/A | Methods with same name grouped together |
| Parallel | JUnit parallel config | TestNG parallel config (interceptor runs before parallel dispatch) |

## Mixed projects

If your project uses both JUnit and TestNG (e.g., migrating between frameworks), the Maven/Gradle plugins will detect both and add both `test-order-junit` and `test-order-testng` modules. Each framework's listener operates independently on its own test classes.
