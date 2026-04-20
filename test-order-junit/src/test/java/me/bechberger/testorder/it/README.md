# Integration Tests for test-order

Real-world integration tests for the test-order JUnit test reordering tool.

## Quick Start

### Build and run all fixtures
```bash
cd test-fixtures
mvn clean test
# Expected: ~61 tests pass (Spring Boot slices, parameterized tests, JaCoCo, shallow clone, parallel execution)
```

### Run integration tests
```bash
cd test-order-junit
mvn test -Dtest=*IT
# Runs: SpringBootSlicesIT, ParameterizedTestOrderingIT, JaCoCoCoexistenceIT, ChangeDetectionEdgeCasesIT, ParallelExecutionIT
```

## What's Included

## What's Included

### Test Fixtures (5 Maven Projects)

Self-contained projects for real-world test-order scenarios:

1. **fixture-spring-boot-slices**
   - Spring Boot application with @DataJpaTest, @WebMvcTest, @SpringBootTest
   - Tests Spring context isolation and slice boundaries
   - 9 tests across 3 test classes

2. **fixture-parameterized-tests**
   - @ParameterizedTest with @CsvSource and @ValueSource
   - Tests method-level scoring with dynamic test IDs
   - 27 parameterized test instances

3. **fixture-jacoco**
   - Configured with both test-order agent and JaCoCo coverage agent
   - Tests dual-agent coexistence and performance
   - 5 tests with coverage report generation

4. **fixture-shallow-clone**
   - Minimal utility project for git edge cases
   - Tests shallow clones, fresh repos, non-git projects
   - 2 tests, git initialized during test execution

5. **fixture-parallel-execution**
   - Three test suites with varied execution times (18 tests total)
   - Maven parallel execution with 4 concurrent threads and forked processes
   - Tests concurrent state file writes and synchronization

### Integration Tests (6 Test Classes)

**Location**: `test-order-junit/src/test/java/me/bechberger/testorder/it/`

1. **BaseFixtureIT** (Abstract base)
   - Shared utilities for fixture management
   - Maven execution and output parsing
   - Assertion helpers for state files and test counts

2. **SpringBootSlicesIT** (4 tests)
   - Full workflow with Spring slices
   - Per-slice isolation tests (@DataJpaTest, @WebMvcTest, @SpringBootTest)

3. **ParameterizedTestOrderingIT** (4 tests)
   - Full workflow with parameterized tests
   - CSV-based and value-based parameterization
   - Duration aggregation validation

4. **JaCoCoCoexistenceIT** (4 tests)
   - Dual-agent coexistence validation
   - Coverage report generation
   - Performance overhead measurement

5. **ChangeDetectionEdgeCasesIT** (5 tests)
   - Non-git projects
   - Fresh single-commit repos
   - Shallow clones
   - Full git history with changes

6. **ParallelExecutionIT** (4 tests)
   - Concurrent test execution with multiple threads
   - Forked process execution validation
   - State file synchronization under contention
   - Test count accuracy with thread interleaving

## Real-World Bug Patterns

These tests validate that test-order handles:

- **Spring context conflicts** when tests are reordered across slice boundaries
- **Parameterized test duration tracking** with [index] suffixes in test IDs
- **Dual-agent attachment** (test-order + JaCoCo) without classpath conflicts
- **Change detection fallback** when git history is incomplete or missing
- **Performance under concurrent execution** with multiple agents

## Design Philosophy

### Fixture Isolation
All fixtures are **self-contained copies**, not references to workspace projects:
- Tests don't depend on workspace state
- Fresh git repos initialized during test execution
- Temp directory cleanup ensures no side effects

### Real Execution
Integration tests use **actual Maven invocation**, not mocked tools:
- Real agent attachment (bytecode instrumentation)
- Real classpath with all dependencies
- Real file I/O (state files, index files)
- Validates end-to-end workflows

### Concrete Assertions
Tests validate **concrete outcomes**, not just success:
- Test counts must match exactly
- State files must exist and be valid
- Coverage reports must be generated
- No silent failures or masked exceptions

## Running Specific Tests

### Run one fixture standalone
```bash
cd test-fixtures/fixture-spring-boot-slices
mvn clean test
```

### Run one integration test
```bash
mvn test -Dtest=SpringBootSlicesIT
```

### Run one integration test method
```bash
mvn test -Dtest=SpringBootSlicesIT#testSpringBootSlicesFull
```

### Run with Maven debug output
```bash
mvn test -Dtest=*IT -X
```

## Troubleshooting

### Integration tests fail with "Fixture not found"
- Ensure you run tests from the project root: `/test-order-junit/`
- Fixtures are at: `../test-fixtures/`

### Integration tests fail with "Maven command not found"
- Ensure `mvn` is in your PATH
- Or use Maven wrapper: `./mvnw` instead of `mvn`

### Integration tests timeout
- Increase timeout: `mvn test -Dtest=*IT -DforkMode=never -Dtimeout=120`
- Some fixtures take 15-30 seconds per test

## Implementation Notes

### State File Format
test-order creates compressed binary state files:
- `.test-order` — Main state file (serialized TestOrderState)
- `.test-order-hashes.lz4` — Compressed dependency index
- `.test-order-test-hashes.lz4` — Test class hashes (change detection)

### Maven Integration
Integration tests use Maven invoker pattern:
- Spawn separate Maven process in temp directory
- Parse stdout for test counts ("Tests run: N")
- Validate no build failures ("BUILD SUCCESS")
- Check for agent-specific errors (NoSuchMethodError, VerifyError)

### Parameterized Test Handling
@ParameterizedTest instances are handled via:
- Method-level score aggregation
- Dynamic test ID generation with [index] suffix
- Per-parameter execution tracked in run records

### Spring Boot Slice Boundaries
Different @*Test annotations create separate Spring contexts:
- @DataJpaTest: Database layer only (minimal context)
- @WebMvcTest: Servlet + Spring tier (MockMvc)
- @SpringBootTest: Full application context
- Tests validate these boundaries are respected under reordering

## Future Enhancements

- [ ] Bug injection tests (Phases 4) to verify ITs catch regressions
- [ ] CI integration (Phase 5) to run on each commit
- [ ] Coverage measurement comparing unit vs integration test coverage
- [ ] Gradle multi-module fixture with concurrent builds
- [ ] Performance baselines and regression detection

## Documentation

- `FIXTURES.md` — Detailed fixture documentation
- `INTEGRATION_TEST_SUMMARY.md` — Complete implementation summary
- `plan.md` — Original planning document

---

**Created**: 2026-04-20  
**Status**: Phases 1-3 complete (fixtures + ITs ready)  
**Next**: Phase 4 (bug injection validation) and Phase 5 (CI integration)
