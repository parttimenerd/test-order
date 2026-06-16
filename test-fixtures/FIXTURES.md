# Test Fixtures Documentation

## Overview

Self-contained integration test fixtures for validating test-order functionality against real-world usage patterns. All fixtures are independent Maven projects that can be built and tested in isolation or as a suite.

## Fixtures

### 1. fixture-spring-boot-slices

**Purpose**: Validate test-order works correctly with Spring Boot test slices (@DataJpaTest, @WebMvcTest, @SpringBootTest) and respects Spring context boundaries.

**Structure**:
- Main code: Pet entity, PetRepository (JPA), PetController (REST)
- Tests:
  - `PetRepositoryDataJpaTest` (@DataJpaTest) - Tests database layer only
  - `PetControllerWebMvcTest` (@WebMvcTest) - Tests REST layer with mocked repository
  - `PetShopIntegrationTest` (@SpringBootTest) - Full integration with all components

**Real-world bug patterns tested**:
- Spring context caching conflicts when tests are reordered
- Test pollution from shared mutable state across slices
- Duration tracking accuracy for tests with different context bootstrap times

**Location**: `test-fixtures/fixture-spring-boot-slices/`

### 2. fixture-parameterized-tests

**Purpose**: Validate test-order correctly handles @ParameterizedTest classes with multiple parameter sets and method-level test IDs.

**Structure**:
- Main code: Calculator utility class with static methods (add, subtract, multiply, divide, isPrime)
- Tests:
  - `CalculatorParameterizedTest` with multiple @ParameterizedTest methods using @CsvSource and @ValueSource
  - Total: ~25 individual test instances (5 CSV rows per method + 8 value source tests each)

**Real-world bug patterns tested**:
- Duration aggregation for parameterized test methods (each parameter set generates separate duration entries)
- Method-level scoring when test IDs include [index] suffixes
- Reordering stability when parameter counts change or parameters are added/removed

**Location**: `test-fixtures/fixture-parameterized-tests/`

### 3. fixture-jacoco

**Purpose**: Validate test-order agent coexists peacefully with JaCoCo code coverage agent. Tests for classpath conflicts, coverage accuracy, and error detection.

**Structure**:
- Main code: StringProcessor utility with methods for string manipulation and analysis
- Tests: `StringProcessorTest` with comprehensive coverage of all branches
- Maven: Configured with both maven-surefire-plugin and jacoco-maven-plugin

**Real-world bug patterns tested**:
- Dual-agent attachment conflicts (shared classloading, instrumentation interference)
- Silent failures masking JaCoCo configuration errors
- Performance regression from dual instrumentation overhead

**Location**: `test-fixtures/fixture-jacoco/`

### 4. fixture-shallow-clone

**Purpose**: Validate change detection gracefully handles edge cases: shallow git clones (missing HEAD~1), fresh single-commit repos, and non-git projects.

**Structure**:
- Minimal: Single Utility class with two methods (greet, square)
- Single test class: UtilityTest with basic tests
- Designed for git initialization during test execution (not a pre-existing repo)

**Real-world bug patterns tested**:
- Graceful fallback from git-based to hash-based change detection when HEAD~1 doesn't exist
- Proper handling of shallow clones (--depth 1) which lack full commit history
- Non-git projects that have no .git directory (fallback to hash store only)

**Location**: `test-fixtures/fixture-shallow-clone/`

### 5. fixture-parallel-execution

**Purpose**: Validate test-order handles concurrent test execution safely. Tests Maven parallel execution modes (multiple threads, forked processes) with synchronized state file writes.

**Structure**:
- Three test classes with deliberately varied execution times
- `ConcurrentTestSuiteA`: 5 tests with varying durations (quick, medium, slow, I/O, final)
- `ConcurrentTestSuiteB`: 6 tests with mixed workloads (fast, medium, slow, I/O-heavy)
- `ConcurrentTestSuiteC`: 7 tests with compute-intensive and blocking operations
- Total: 18 test instances running concurrently

**Maven Configuration**:
- `<parallel>methods</parallel>` - Multiple test methods run in parallel threads
- `<threadCount>4</threadCount>` - 4 concurrent threads
- `<forkMode>perthread</forkMode>` - Each thread gets its own JVM process
- `<forkCount>2</forkCount>` - 2 forked processes

**Real-world bug patterns tested**:
- Concurrent state file writes from multiple threads (TestOrderState.addRunRecord() synchronization)
- Race conditions when threads simultaneously write timing/scoring metadata
- Data loss scenarios if state file synchronization is broken
- Correct test count tracking despite thread interleaving
- Accurate duration tracking even with process forking

**Location**: `test-fixtures/fixture-parallel-execution/`

### 6. fixture-kotest

**Purpose**: Validate test-order workflows on Kotlin projects that use Kotest on the JUnit platform.

**Structure**:
- Main code: `DiscountPolicy` Kotlin object with tier-based discount logic
- Tests: `DiscountPolicyKotestTest` using Kotest `StringSpec` with 5 test cases

**Real-world bug patterns tested**:
- Kotest discovery/execution through Maven Surefire + JUnit platform
- test-order learn/order goals on Kotlin test suites that are not JUnit Jupiter classes
- Stability of test counts and workflow completion in mixed Kotlin/JVM setups

**Location**: `test-fixtures/fixture-kotest/`

## Usage

### Build all fixtures
```bash
cd test-fixtures
mvn clean test
```

### Build individual fixture
```bash
cd test-fixtures/fixture-spring-boot-slices
mvn clean test
```

### With test-order enabled (once integration is complete)
```bash
cd test-fixtures/fixture-spring-boot-slices
mvn clean test-order:combined
```

## Integration with test-order

**Note**: Fixtures are currently standalone projects. Once integration tests are written, they will:
1. Copy/reference each fixture
2. Run test-order learn mode to build dependency index
3. Run test-order order mode to reorder tests
4. Verify reordering actually happened (order change from first to second run)
5. Run test-order affected mode to validate subset selection
6. Validate that all modes complete without errors

## Directory Structure

```
test-fixtures/
├── pom.xml                           (parent POM, aggregates all fixtures)
├── fixture-spring-boot-slices/
│   ├── pom.xml
│   ├── src/main/java/com/example/petshop/
│   │   ├── Pet.java
│   │   ├── PetRepository.java
│   │   ├── PetController.java
│   │   └── PetShopApplication.java
│   ├── src/test/java/com/example/petshop/
│   │   ├── PetRepositoryDataJpaTest.java
│   │   ├── PetControllerWebMvcTest.java
│   │   └── PetShopIntegrationTest.java
│   └── src/test/resources/
│       └── application.properties
├── fixture-parameterized-tests/
│   ├── pom.xml
│   ├── src/main/java/com/example/math/
│   │   └── Calculator.java
│   └── src/test/java/com/example/math/
│       └── CalculatorParameterizedTest.java
├── fixture-jacoco/
│   ├── pom.xml
│   ├── src/main/java/com/example/coverage/
│   │   └── StringProcessor.java
│   └── src/test/java/com/example/coverage/
│       └── StringProcessorTest.java
├── fixture-kotest/
│   ├── pom.xml
│   ├── src/main/kotlin/com/example/kotest/
│   │   └── DiscountPolicy.kt
│   └── src/test/kotlin/com/example/kotest/
│       └── DiscountPolicyKotestTest.kt
├── fixture-shallow-clone/
│   ├── pom.xml
│   ├── src/main/java/com/example/sample/
│   │   └── Utility.java
│   └── src/test/java/com/example/sample/
│       └── UtilityTest.java
└── fixture-parallel-execution/
    ├── pom.xml
    ├── src/test/java/me/bechberger/testorder/
    │   ├── ConcurrentTestSuiteA.java
    │   ├── ConcurrentTestSuiteB.java
    │   └── ConcurrentTestSuiteC.java
    └── target/
```

## Future: Integration Tests

These fixtures will be used by integration tests in `test-order-junit/src/test/java/` (or similar) that exercise:

1. **SpringBootSlicesIT** — Spring Boot slice ordering and context isolation
2. **ParameterizedTestOrderingIT** — Parameterized test duration tracking and method-level ordering
3. **JaCoCoCoexistenceIT** — Dual-agent coexistence and coverage accuracy
4. **ChangeDetectionEdgeCasesIT** — Shallow clones, fresh repos, non-git projects
5. **MavenInvokerIT** — End-to-end Maven invoker test (learn → order → select → combined)
6. **GradleInvokerIT** — End-to-end Gradle invoker test

Each IT will:
- Initialize a fresh git repository (if needed) with specific commit history
- Run test-order in multiple modes sequentially
- Validate output (test order changed, correct subsets selected, no errors)
- Assert on concrete outcomes (not just "no exception thrown")

---

**Created**: 2026-04-20
**Status**: Phase 2 complete (fixtures built and ready for IT integration)
