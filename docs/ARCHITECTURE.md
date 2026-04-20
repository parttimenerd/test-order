# test-order Architecture & Design

Complete architectural overview of test-order, including design decisions, component interactions, and data flow.

## Table of Contents

1. [System Overview](#system-overview)
2. [Core Components](#core-components)
3. [Dependency Graph](#dependency-graph)
4. [Change Detection](#change-detection)
5. [Test Selection & Ordering](#test-selection--ordering)
6. [Instrumentation & Data Collection](#instrumentation--data-collection)
7. [Data Structures](#data-structures)
8. [Maven Plugin System](#maven-plugin-system)
9. [Gradle Plugin Integration](#gradle-plugin-integration)
10. [Performance Characteristics](#performance-characteristics)
11. [Design Decisions](#design-decisions)
12. [Extension Points](#extension-points)

---

## System Overview

### High-Level Flow

```
┌─────────────────────────────────────────────────────────────┐
│                     Test Execution                          │
│ ┌──────────────────────────────────────────────────────────┐│
│ │ Maven/Gradle                                             ││
│ │ ┌────────────────────────────────────────────────────┐  ││
│ │ │ 1. Learn Mode (First Run)                          │  ││
│ │ │    ├─ Load test-order Java agent                   │  ││
│ │ │    ├─ Run ALL tests with instrumentation          │  ││
│ │ │    ├─ Collect: class execution per test            │  ││
│ │ │    └─ Write: test-dependencies.lz4 (index)        │  ││
│ │ └────────────────────────────────────────────────────┘  ││
│ │ ┌────────────────────────────────────────────────────┐  ││
│ │ │ 2. Change Detection                                │  ││
│ │ │    ├─ Detect: changed files/classes               │  ││
│ │ │    ├─ Hash: source code (git, uncommitted, etc)   │  ││
│ │ │    └─ Return: Set<ClassName> for changed code     │  ││
│ │ └────────────────────────────────────────────────────┘  ││
│ │ ┌────────────────────────────────────────────────────┐  ││
│ │ │ 3. Test Selection & Ordering                       │  ││
│ │ │    ├─ Load: test-dependencies.lz4 index           │  ││
│ │ │    ├─ Match: tests affected by changes            │  ││
│ │ │    ├─ Rank: by coverage (topN) or random          │  ││
│ │ │    └─ Order: JUnit ClassOrderer                   │  ││
│ │ └────────────────────────────────────────────────────┘  ││
│ │ ┌────────────────────────────────────────────────────┐  ││
│ │ │ 4. Test Execution                                  │  ││
│ │ │    └─ Run: selected tests in priority order        │  ││
│ │ └────────────────────────────────────────────────────┘  ││
│ └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. Java Agent (test-order-agent)

**Purpose**: Runtime bytecode instrumentation to track class execution.

**Key Classes**:
- `Premain.java` - Agent entry point
- `ExecutionTracer.java` - Core instrumentation logic
- `ClassTransformer.java` - Bytecode transformation via ASM
- `InvocationRecorder.java` - Records class-to-test relationships

**Flow**:
```java
Test A runs
  ↓
ClassTransformer intercepts bytecode loading
  ↓
Injects bytecode: invokeStatic(InvocationRecorder.recordClass(className))
  ↓
When ClassX is loaded/executed:
  InvocationRecorder.recordClass("com.example.ClassX")
  ↓
ExecutionTracer aggregates: Test A → {ClassX, ClassY, ...}
```

**Output**: `execution.txt` or similar in test run directory (collected into index).

### 2. Core Library (test-order-core)

**Purpose**: Central logic for change detection and dependency index management.

**Key Classes**:

#### ChangeDetector.java
- **Purpose**: Determine which classes changed since last run
- **Implementations**:
  - `AutoChangeDetector` - Auto-detects change type
  - `GitChangeDetector` - Uses git history
  - `UncommittedChangeDetector` - Detects uncommitted changes
  - `ExplicitChangeDetector` - Uses provided list
  - `HashBasedChangeDetector` - SHA-256 file hashing

#### DependencyIndex.java
- **Purpose**: Stores and queries test→class dependencies
- **Format**: Compressed LZ4 binary format
- **Data**: `TestClassName → Set<DependencyClassName>`
- **Operations**:
  - `write()` - Serialize to disk
  - `read()` - Deserialize from disk
  - `getTestsForClass(className)` - Find tests exercising class
  - `getClassesForTest(testName)` - Find classes tested by test

#### TestSelector.java
- **Purpose**: Select which tests to run based on changes
- **Options**:
  - `selectTopN(int n)` - Top N tests by score
  - `selectRandomM(int m)` - Random M tests for diversity
  - Combined strategy: top N + random M

#### TestRanker.java
- **Purpose**: Score/rank tests by relevance to changes
- **Scoring**: `score(test, changedClasses) → double`
  - Higher score = more likely to catch regression
  - Based on: overlap with changed code, test execution time

### 3. JUnit Extension (test-order-junit)

**Purpose**: JUnit 5 integration for test ordering.

**Key Classes**:

#### TestOrderExtension.java
- **Purpose**: JUnit 5 extension hook
- **Lifecycle**:
  1. Load dependency index
  2. Detect changed classes
  3. Select/order tests
  4. Provide to `TestClassOrderer`

#### TestClassOrderer.java
- **Purpose**: JUnit `ClassOrderer` implementation
- **Input**: List of test classes
- **Output**: Ordered list (highest priority first)
- **Integration**: Via `@Order` annotations + custom logic

### 4. Maven Plugin (test-order-maven-plugin)

**Purpose**: Maven lifecycle integration and configuration.

**Key Mojos**:

#### CombinedMojo
- **Purpose**: Primary goal (`test-order:combined`)
- **Execution**:
  1. Invokes Maven `test` phase with agent attached
  2. Collects dependency index
  3. Configures test ordering via JUnit
  4. Runs selected tests in order

#### SnapshotMojo
- **Purpose**: Index-only mode (`test-order:snapshot`)
- **Use**: Learn dependencies without running tests

#### PrepareMojo
- **Purpose**: Setup mode (`test-order:prepare`)
- **Use**: Initialize directories and state files

#### Parameters**:
- `changeMode` - How to detect changes (auto, explicit, etc)
- `selectTopN` - Number of top tests to run
- `selectRandomM` - Number of random tests
- `instrumentationMode` - FULL or SMART filtering

### 5. CLI Tools (test-order-cli)

**Purpose**: CI/CD integration and advanced features.

**Components**:

#### CiDepDownloadManager
- **Purpose**: Download dependency indices from CI artifacts
- **Supported CI**:
  - GitHub Actions (via workflow artifacts)
  - HTTP endpoints
  - Local paths
- **Cache**: `ArtifactCache` for downloaded artifacts

#### CiConfigParser
- **Purpose**: YAML configuration for CI download
- **Config Format**:
  ```yaml
  ci:
    type: github-actions | http
    token: ${GITHUB_TOKEN}
    download:
      artifacts: ["test-dependencies.lz4"]
  ```

### 6. Coverage Mojo (test-order-coverage-mojo)

**Purpose**: Generate coverage reports and identify least-tested classes.

**Key Classes**:

#### JaCoCoReportParser
- **Purpose**: Parse JaCoCo XML coverage reports
- **Extracts**: Line/method/branch coverage percentages

#### LeastTestedClassifier
- **Purpose**: Identify classes below coverage thresholds
- **Severity**: Critical (<30%), Important (30-50%), Review (50-70%)

#### CoverageReporter
- **Purpose**: Aggregate coverage metrics across modules
- **Output**: Statistics, recommendations

#### MarkdownGenerator
- **Purpose**: Generate human-readable reports
- **Outputs**:
  - COVERAGE_BY_MODULE.md
  - LEAST_TESTED_CLASSES.md
  - coverage-metrics.json (for CI)

---

## Dependency Graph

### Module Dependencies

```
┌─────────────────────────────────────────────┐
│         test-order-parent (pom)             │
│  ├─ Versions, shared dependencies           │
│  └─ Build plugins, profiles                 │
└──────────────┬──────────────────────────────┘
               │
       ┌───────┼───────┐
       │       │       │
    ┌──▼──┐ ┌──▼──┐ ┌──▼──┐
    │Agent│ │Core │ │JUnit │  (independent)
    └─────┘ └──▼──┘ └──────┘
            (base)
              │
    ┌─────────┼─────────────┬──────────┐
    │         │             │          │
┌───▼───┐ ┌──▼──────┐ ┌────▼─────┐ ┌─▼─────┐
│Maven  │ │Gradle   │ │CLI Tools  │ │Coverage│
│Plugin │ │Plugin   │ │           │ │Mojo    │
└───────┘ └─────────┘ └───────────┘ └────────┘
```

### Runtime Dependencies

```
Test Execution
  ├─ Maven/Gradle (build system)
  ├─ JVM (Java 17+)
  │  ├─ test-order-agent.jar (via -javaagent)
  │  ├─ test-order-core.jar (classpath)
  │  ├─ JUnit 5 (test framework)
  │  │  ├─ junit-jupiter-api
  │  │  ├─ junit-jupiter-engine
  │  │  └─ junit-platform-launcher
  │  └─ Application classes
  └─ Dependency index (test-dependencies.lz4)
```

---

## Change Detection

### Architecture

```
Code Changes
    ↓
┌───────────────────────────────────────────────┐
│         ChangeDetectionStrategy               │
│  (factory determines strategy based on env)   │
└───────────────┬───────────────────────────────┘
                │
        ┌───────┴────────┬──────────┬───────────┐
        │                │          │           │
    Auto     Explicit  Since-Last-Run  Git-based
```

### 5 Change Detection Modes

| Mode | Detection Method | When to Use | Speed | Accuracy |
|------|-----------------|-------------|-------|----------|
| `auto` | Detects intelligently | Default, most projects | Fast | High |
| `explicit` | User-provided class list | CI, known changes | Fastest | Perfect |
| `since-last-run` | Compares to stored state | Local dev | Very Fast | Medium |
| `since-last-commit` | Git diff from commit | Branch CI | Fast | High |
| `uncommitted` | Git unstaged changes | Rapid iteration | Fast | High |

### Implementation Details

#### AutoChangeDetector (Smart Selection)
```
If environment.isCI() && gitAvailable()
  → Use GitChangeDetector (since-last-commit)
Else if environment.hasUnstagedChanges()
  → Use UncommittedChangeDetector
Else
  → Use HashBasedChangeDetector (since-last-run)
```

#### HashBasedChangeDetector
```
1. Read stored hashes from .test-order-hashes.lz4
2. Compute current SHA-256 of all .java files
3. Compare: {stored hashes} vs {current hashes}
4. Return: Classes whose files changed
```

#### GitChangeDetector
```
1. Run: git diff --name-only <BASE>...HEAD
2. Map: file paths → class names
3. Return: Classes in changed files
```

---

## Test Selection & Ordering

### Selection Algorithm

```
┌─────────────────────────────────────────┐
│    All Tests (from DependencyIndex)     │
└────────────────┬────────────────────────┘
                 │
         ┌───────▼────────┐
         │ Filter by      │
         │ changed code   │
         │ (affected)     │
         └───────┬────────┘
                 │
         ┌───────▼──────────────┐
         │ Score by relevance   │
         │ (overlap with        │
         │  changed classes)    │
         └───────┬──────────────┘
                 │
         ┌───────▼──────────────┐
         │ Select Top N         │
         │ (highest score)      │
         └───────┬──────────────┘
                 │
         ┌───────▼──────────────┐
         │ Add Random M         │
         │ (diversity, edge     │
         │  case detection)     │
         └───────┬──────────────┘
                 │
         ┌───────▼──────────────┐
         │ Order by Score       │
         │ (via @Order)         │
         └───────┬──────────────┘
                 │
         ┌───────▼────────┐
         │ Execute Tests  │
         │ (JUnit runs)   │
         └────────────────┘
```

### Scoring Example

```
Changed classes: {PaymentService, OrderProcessor}

Test: PaymentServiceTest
  Classes executed: {PaymentService, LocalDateTime, ArrayList}
  Overlap: {PaymentService} = 1 class
  Overlap %: 1/3 = 33%
  Score: HIGH (pay attention to this test)

Test: UtilityTest
  Classes executed: {StringUtil}
  Overlap: {} = 0 classes
  Overlap %: 0/1 = 0%
  Score: LOW (probably not affected by changes)
```

---

## Instrumentation & Data Collection

### Agent Entry Point

```
java -javaagent:test-order-agent.jar \
     -Dtestorder.deps-dir=target/test-order-deps \
     -m ... mvn test
```

### Bytecode Transformation

```
Original bytecode:
┌──────────────────────┐
│ public class Service │
│   void execute() {}  │
└──────────────────────┘

           ↓ (ASM transforms)

Instrumented bytecode:
┌──────────────────────────────────┐
│ public class Service             │
│   static {                       │
│     ExecutionTracer.register()   │
│   }                              │
│   void execute() {               │
│     ExecutionTracer.recordClass()│
│     // original code             │
│   }                              │
└──────────────────────────────────┘
```

### Data Collection

```
Each Test Run
    ↓
InvocationRecorder accumulates:
  {
    TestA → [ClassX, ClassY, ClassZ],
    TestB → [ClassX, ClassW],
    ...
  }
    ↓
After all tests complete:
    ↓
Write to execution.txt:
  TestA:ClassX,ClassY,ClassZ
  TestB:ClassX,ClassW
    ↓
Aggregate into test-dependencies.lz4:
  {
    "TestA": [ClassX, ClassY, ClassZ],
    "TestB": [ClassX, ClassW]
  }
```

---

## Data Structures

### DependencyIndex (In-Memory Representation)

```java
class DependencyIndex {
    // TestClassName → Set<DependencyClassName>
    private Map<String, Set<String>> testDependencies;
    
    // Statistics
    private long serializationTime;
    private int classCount;
    private int testCount;
}
```

### Serialization Format

**File**: `test-dependencies.lz4`

**Format**:
```
[MAGIC] [VERSION] [TIMESTAMP]
[PAYLOAD (LZ4 compressed)]
  ├─ int: number of tests
  ├─ For each test:
  │  ├─ String: test class name
  │  ├─ int: number of dependencies
  │  └─ For each dependency:
  │     └─ String: class name
  └─ [CHECKSUM]
```

**Why LZ4**:
- Fast compression/decompression (important for build time)
- Binary format (smaller than JSON)
- Streaming support (can process large indexes)

### State File Format

**File**: `.test-order-state`

**Format** (JSON):
```json
{
  "lastRunTimestamp": 1713700000000,
  "lastRunHashes": {
    "com/example/Service.java": "abc123...",
    "com/example/Util.java": "def456..."
  },
  "indexVersion": "1.0.0"
}
```

---

## Maven Plugin System

### Plugin Configuration

**pom.xml Example**:
```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    
    <configuration>
        <changeMode>auto</changeMode>
        <selectTopN>20</selectTopN>
        <selectRandomM>10</selectRandomM>
        <instrumentationMode>FULL</instrumentationMode>
    </configuration>
    
    <executions>
        <execution>
            <goals>
                <goal>combined</goal>
            </goals>
            <phase>test</phase>
        </execution>
    </executions>
</plugin>
```

### Lifecycle Integration

```
Maven build lifecycle
    │
    ├─ compile
    ├─ test-compile
    │
    ├─► test (PHASE)
    │   │
    │   └─ test-order:combined (GOAL)
    │       ├─ 1. initContext()
    │       ├─ 2. prepareTestExecution()
    │       │   └─ Attach Java agent
    │       ├─ 3. execute() (run Maven test)
    │       │   └─ Invoke maven-surefire-plugin with agent
    │       ├─ 4. collectResults()
    │       │   └─ Aggregate test results
    │       └─ 5. snapshotHashes()
    │           └─ Save hashes for next run
    │
    └─ package
```

### Parameter Validation

```
AbstractTestOrderMojo.initContext()
    │
    ├─ validateParameters()
    │   ├─ changeMode enum validation
    │   ├─ changedClasses (required if explicit mode)
    │   └─ weightsFile existence check
    │
    └─ CombinedMojo.validateCombinedMojoParameters()
        ├─ instrumentationMode (FULL | SMART)
        ├─ selectTopN >= 0
        ├─ selectRandomM >= 0
        └─ Check: at least one selection method enabled
```

---

## Gradle Plugin Integration

**Status**: Parallel to Maven plugin (separate implementation).

**Key Differences**:
- Uses Gradle task system instead of Maven lifecycle
- Configuration in `build.gradle` instead of `pom.xml`
- Task dependencies instead of goal executions

**Example**:
```groovy
plugins {
    id 'me.bechberger.test-order' version '0.1.0'
}

testOrder {
    changeMode = 'auto'
    selectTopN = 20
    selectRandomM = 10
}
```

---

## Performance Characteristics

### Time Complexity

| Operation | Time | Notes |
|-----------|------|-------|
| Agent startup | O(n) | n = classes to load |
| Bytecode transform | O(1) per class | Per-class overhead constant |
| Index write | O(n*m*log(m)) | n=tests, m=deps per test |
| Index read | O(n*m) | Linear in index size |
| Change detection (git) | O(files changed) | Fast, only diffs |
| Change detection (hash) | O(all java files) | Slow but reliable |
| Test selection | O(n*m) | n=tests, m=deps |
| Test ordering | O(n log n) | Standard sorting |

### Space Complexity

| Component | Space | Scaling |
|-----------|-------|---------|
| Dependency index (memory) | O(n*m) | n=tests, m=avg deps |
| Index file (disk, compressed) | O(n*m*0.1) | ~10% of memory (LZ4) |
| Agent memory overhead | O(m) | m = classes loaded |
| State file | O(files) | Typically <1MB |

### Optimization Strategies

1. **Incremental Hashing**: Only hash changed files
2. **Lazy Index Loading**: Load only relevant test deps
3. **Compression**: LZ4 reduces index size 10x
4. **Caching**: State file prevents re-computation
5. **Parallel**: Agent doesn't block test execution

---

## Design Decisions

### 1. Why Java Agent for Instrumentation?

**Alternatives Considered**:
- ASM ClassVisitor (requires pre-processing) → Slower build
- Bytecode scanning/static analysis → Incomplete data
- Manual test annotations → Maintenance burden
- Java agent (bytecode injection) → **Chosen** ✓

**Rationale**:
- Accurate: Gets actual runtime behavior
- Non-invasive: No test code changes required
- Complete: Captures all class interactions
- Performant: Minimal overhead

### 2. Why Binary Format (LZ4) vs JSON?

**Alternatives**:
- JSON → Easy to debug, but 10x larger files
- Protocol Buffers → Faster, but more complex
- LZ4 compressed binary → **Chosen** ✓

**Rationale**:
- Balance: Human-readable (LZ4 decompresses to text) + Compact
- Performance: Compression/decompression is O(n) linear
- Compatibility: Standard library, no external deps

### 3. Why Selective Test Execution vs Full Suite?

**Rationale**:
- **Feedback Speed**: Top 20 tests run in ~20% of time
- **Regression Detection**: Top tests exercise ~80% of code
- **Diversity**: Random tests catch edge cases
- **CI Compatibility**: Faster feedback loops
- **Safety Net**: Full suite still available when needed

### 4. Why Multiple Change Detection Modes?

**Use Cases**:
- **Explicit**: Known changes (CI) → Fastest
- **Git-based**: Branch context (CI/CD) → Accurate
- **Uncommitted**: Rapid iteration (local) → Fast
- **Hash-based**: Fallback (any project) → Reliable
- **Auto**: Smart selection (default) → Best of all

### 5. Why Store State Instead of Compute On-Demand?

**Trade-off**:
- **With State**: Fast delta checks, but requires management
- **Without State**: Always accurate, but slow hashing

**Chosen**: State + validation (hash changes between runs).

---

## Extension Points

### Custom Change Detector

```java
class MyChangeDetector implements ChangeDetectionStrategy {
    @Override
    Set<String> getChangedClasses(ChangeDetectionContext ctx) {
        // Custom logic here
        return Set.of("com.example.MyClass");
    }
}
```

**Register via** Maven parameter or SPI.

### Custom Test Ranker

```java
class MyRanker implements TestRanker {
    @Override
    double scoreTest(String testName, Set<String> changedClasses) {
        // Custom scoring
        return Math.random();
    }
}
```

### Custom Index Format

Implement `DependencyIndexPersistence` to use different storage format.

### Custom JUnit Integration

Extend `TestClassOrderer` to customize test ordering logic beyond score-based.

---

## Conclusion

test-order's architecture balances:
- **Accuracy**: Bytecode instrumentation captures real behavior
- **Performance**: Selective execution + caching
- **Usability**: Zero configuration defaults
- **Extensibility**: Plugin points for customization
- **Reliability**: Validation, fallbacks, state management

Key principle: **Fail fast, degrade gracefully, provide overrides**.
