# test-order Realistic Test Plan

End-to-end validation of the Maven/Gradle plugins and core library against real-world projects already present in the workspace. Each test fixture is derived from an existing project to exercise scenarios that synthetic examples cannot.

---

## Missing Test Coverage

Systematic audit of every production class against its test coverage. Classes are grouped by severity: untested algorithms that affect correctness first, then untested wiring/glue, then gaps in existing tests.

### Tier 1 — Untested algorithms (correctness risk)

| Class | Module | Lines | Status | What's missing / was missing |
|-------|--------|------:|--------|------------------------------|
| **SetCoverComputer** | core | 81 | ✅ 22 tests | `SetCoverComputerTest` covers greedy correctness, empty universe, empty coverage, single-test, identical sets, stale queue entries (E16–E20). |
| **StructuralChangeAnalyzer** | core/changes | 270 | ✅ 53 tests | `StructuralChangeAnalyzerTest` covers `fromDiffs()`, `computeOverlap()` variants, `<clinit>` handling, empty diffs, type changes (E48–E50). |
| **ChangeComplexity** | core/changes | 186 | ✅ 25+ tests | `ChangeComplexityTest` now covers `compute()` with member-level blending, `findSourceFile()` inner-class resolution, `serialise()`/`deserialise()` round-trip, `fromRawSizes()` normalization, deflate-size invariants (E45–E47). |
| **MethodHashStore** | core/changes | 80 | ✅ 39 tests | `MethodHashStoreTest` covers scan/save/load round-trip, `getChangedMethods()`, cross-platform paths, special characters (E37–E38). |
| **LineDiff** | core/changes | 95 | ✅ 52 tests | `LineDiffTest` covers LCS correctness, null inputs, identical texts, one-empty, single-line (E41–E44). |
| **PriorityMethodOrderer** | junit | 102 | ✅ 10 tests | `PriorityMethodOrdererTest` covers `setPendingState()`, `clearPendingState()`, `orderMethods()` with scores/failures/durations, ties, empty list, graceful degradation (E25–E27). |

### Tier 2 — Untested plugin/agent wiring

| Class | Module | Lines | What's missing |
|-------|--------|------:|----------------|
| **PrepareMojo** | maven-plugin | 184 | ✅ Unit tests exist in `PrepareMojoTest`: mode validation, auto-learn thresholds, and agent argLine injection safeguards are covered. Remaining gap: deeper Surefire configuration paths still mostly IT-only. |
| **CombinedMojo** | maven-plugin | 155 | ✅ Added unit tests in `CombinedMojoTest`: no-index learn fallback, empty-selection list writing, property propagation for `testorder.remaining.file`/`testorder.combined.active`, and snapshot path execution. Remaining gap: richer optimization behavior under realistic histories. |
| **SelectMojo** | maven-plugin | 100 | ✅ Added unit tests in `SelectMojoTest`: missing-index failure path, `topN=0`/`randomM=0` empty-selection handling, selected/remaining file writing, and skip-tests behavior. |
| **OptimizeMojo** | maven-plugin | 48 | ✅ Added unit tests in `OptimizeMojoTest`: missing-state failure, malformed-state load failure, and insufficient-history guard path covered. |
| **ShowOrderMojo** | maven-plugin | 239 | ✅ Added unit tests in `ShowOrderMojoTest`: no-index failure path and basic successful ordering/rendering path with a simple dependency index. |
| **RunRemainingMojo** | maven-plugin | 50 | ✅ Added unit tests in `RunRemainingMojoTest`: missing/empty remaining-file skip behavior, non-empty file include propagation to Surefire, and read-error wrapping. |
| **DumpMojo** | maven-plugin | 34 | ✅ Added unit tests in `DumpMojoTest`: missing-index error path, empty-index graceful handling, and dump-to-file output verified. |
| **AggregateMojo** | maven-plugin | 34 | ✅ Added unit tests in `AggregateMojoTest`: missing deps-directory failure, empty `.deps` non-overwrite guard, and successful aggregation from `.deps` files. |
| **SnapshotMojo** | maven-plugin | 25 | ✅ Added unit tests in `SnapshotMojoTest`: no-op for missing roots and snapshot creation for existing main/test roots. |
| **ChangeDetectionHelper** | maven-plugin | 170 | ✅ Added unit tests in `ChangeDetectionHelperTest`: mode parsing, invalid mode error, source/test root resolution fallback, explicit changed-class detection, and explicit test-class skip behavior. Remaining gap: richer ReactorContext propagation paths. |
| **TestOrderPlugin** | gradle-plugin | 500+ | ✅ Integration coverage exists in `TestOrderPluginIntegrationTest`/`SpringBootCoreModulesIT` (including show-order/select/run-remaining/optimize paths), plus unit-style registration/idempotency checks in `TestOrderPluginTaskRegistrationTest`. Residual gap: deeper isolated unit tests for agent extraction/repository internals. |
| **TestOrderExtension** | gradle-plugin | ~100 | ✅ Covered by `TestOrderExtensionTest` (defaults and explicit override preservation). |
| **PackageDetector** | gradle-plugin | 97 | ✅ Covered by `PackageDetectorTest` (deep single-child scan, `minimisePrefixes()`, groupId fallback, merged-prefix minimization). |
| **Agent** | agent | ~150 | ✅ Covered by `AgentTest` (argument parsing, invalid args fail-fast, missing runtime JAR handling, bootstrap append, AgentLogger reflection setup). |
| **AgentLogger** | agent/runtime | 50 | ✅ Covered by `AgentLoggerTest` (missing-dir handling, concurrent `setVerboseFile()`, and write-failure disabling behavior). |
| **PersistenceSupport** | core | 32 | ✅ Added `PersistenceSupportTest`: `temporarySibling()`, `resolveLoadPath()` target/temp/neither cases, and `moveIntoPlace()` replacement behavior. Residual gap: explicit `ATOMIC_MOVE` failure fallback path is filesystem-dependent. |

### Tier 3 — Gaps in existing test coverage

| Class | Test File | What's missing |
|-------|-----------|----------------|
| **TestScorer** | DepsAndScoringTest | ✅ 100 tests total. `complexityScore()`, `computeSetCoverBonuses()`, `speedRatio()`, static-field overlap bonus now covered. |
| **MethodScorer** | DepsAndScoringTest | ✅ Set-cover bonus, dep-overlap, empty-methods-list, and all-unknown-durations paths now covered. |
| **PriorityClassOrderer** | PriorityClassOrdererTest | ✅ Gaps covered: `resolveChangedMethods()` tested, structural-diff IOException fallback tested, inner-class ClassDescriptor entry tested, config-fallback-to-defaults tested. 6 new tests added. |
| **TelemetryListener** | TelemetryListenerTest | ✅ Gaps covered: agent-unavailable graceful degradation (order mode + learn mode) tested, interleaved concurrent class execution tested. 3 new tests added. |
| **TestSelector** | TestSelectorTest | ✅ Expanded to 11 tests. Now covers empty input, all-new, topN > total, randomM > available, union completeness (E11–E15). |
| **DependencyMap** | DependencyMapTest | ✅ Gaps covered: row-dedup immutability tested (save/load and in-memory), truncated and garbage binary load throw IOException tested. 4 new tests added. |
| **TestOrderState** | TestOrderStateTest | ✅ Expanded to 57 tests. Covers EMA alpha smoothing (including alpha=0/1 edge cases), failure pruning, APFD edge cases (empty/no-failures), optimize() with insufficient data, schema version, weights I/O (E2–E7 addressed). |
| **FileHashStore** | FileHashStoreTest | ✅ Gaps covered: round-trip forward-slash preservation tested, no-false-positive regression guard tested, truncated and random-bytes LZ4 load throw IOException tested. 4 new tests added. |
| **ChangeDetector** | ChangeDetectorTest | ✅ Gaps covered: single-commit repo (HEAD~1 missing) falls back gracefully tested. Git-not-installed path covered by existing `gitModesFallBackToHashDetectionOutsideGitRepo`. 1 new test added. |
| **Tool** | ToolTest | ✅ Added subprocess guard test for missing subcommand (`System.exit(1)` path) in `ToolTest`, verifying exit code and error message without terminating the test JVM. |

### Summary

| Category | Classes | Status | Risk |
|----------|--------:|--------|------|
| Untested algorithms (Tier 1) | 6 | 6/6 done ✅ | High — silent wrong ordering |
| Untested wiring (Tier 2) | 16 | 16/16 covered ✅ | Medium — some paths still validated primarily via integration tests |
| Gaps in tested code (Tier 3) | 11 | 11/11 addressed ✅ | Medium — edge-case regressions |
| **Total gaps** | **33** | | |

**Highest-impact tests to write first:**
1. ✅ `SetCoverComputerTest` — 22 tests (E16–E20 covered)
2. ✅ `StructuralChangeAnalyzerTest` — 53 tests (E48–E50 covered)
3. ✅ `PriorityMethodOrdererTest` — 10 tests (E25–E27 covered)
4. ✅ `MethodHashStoreTest` — 39 tests (E37–E38 covered)
5. ✅ `ChangeComplexityTest` — 25+ tests covering all missing paths (E45–E47)
6. ✅ `LineDiffTest` — 52 tests (E41–E44 covered)
7. ✅ `TestSelectorTest` (expanded) — 11 tests (E11–E15 covered)
8. ✅ `TestOrderStateTest` (expanded) — 44 tests (E2–E7 addressed)

---

## Goals

1. Prove that learn → order → select → combined workflows actually reorder tests correctly
2. Detect regressions when test-order interacts with Spring contexts, JaCoCo agents, parameterized tests, large dependency graphs, and multi-module reactors
3. Validate change detection (git, hash, explicit) against real edit patterns
4. Catch performance problems early (agent overhead, index load time, scoring latency)
5. Automate everything — every fixture must be runnable from CI with pass/fail

---

## Fixture Overview

| Fixture | Derived From | Build | Tests | Exercises |
|---------|-------------|-------|-------|-----------|
| **F1** petclinic | spring-petclinic | Maven | ~15 classes | Spring slices, JaCoCo, bug injection, full workflow |
| **F2** langchain4j-core | langchain4j/langchain4j-core | Maven | ~164 classes | Large dependency graph, parameterized tests, select mode |
| **F3** starrocks-fe-subset | starrocks/fe/fe-core | Gradle | ~50 classes (curated) | Gradle plugin, 1000+ source classes, performance |
| **F4** multi-module-spring | spring-ai (3 modules) | Maven | ~30 classes | ReactorContext, cross-module state, aggregate |
| **F5** petclinic-gradle | spring-petclinic (ported) | Gradle | ~15 classes | Gradle plugin parity with Maven |

Each fixture lives in `test-fixtures/` and is exercised through JUnit integration tests plus Maven/Gradle invokers.

---

## F1 — Spring Petclinic (Maven, Full Workflow)

**Source:** `spring-petclinic/` (Spring Boot 4.0.3, Java 17, 15 test classes)

**Why this project:** Exercises @WebMvcTest, @DataJpaTest, @SpringBootTest slices, JPA entities, controllers, validators — a layered architecture where dependency-aware reordering matters. Already has a working demo script (`demo-petclinic.sh`).

### Setup

1. Copy `spring-petclinic/` to `test-fixtures/petclinic/`
2. Strip database integration tests that need Docker (MySqlIntegrationTests, PostgresIntegrationTests, MysqlTestApplication)
3. Add test-order-maven-plugin to pom.xml (automated by script, as in `demo-petclinic.sh`)
4. Keep JaCoCo enabled (already configured with 0.8.14)

### Test Scenarios

#### F1.1 — Learn mode produces valid index
```
mvn test -Dtestorder.mode=learn \
  -Dcheckstyle.skip -Dspring-javaformat.skip
```
**Assert:**
- `test-dependencies.lz4` exists and is > 1 KB
- `.test-order-hashes.lz4` exists
- `target/test-order-deps/*.deps` files created (one per test class)
- All tests pass (exit code 0)
- `test-order:dump` produces readable output listing all test classes

#### F1.2 — Order mode with no changes (baseline)
```
mvn test -Dtestorder.mode=order -Dtestorder.changeMode=since-last-run
```
**Assert:**
- All tests pass
- `.test-order-state` written with run record (durations, APFD)
- No test reordering (no changed classes ⇒ default score ordering)

#### F1.3 — Order mode with bug injection
Inject a bug in `Owner.getAddress()` (return `"INJECTED_BUG"`), then:
```
mvn test -Dtestorder.mode=order -Dtestorder.changeMode=uncommitted || true
```
**Assert:**
- OwnerControllerTests runs first (or in top 3) — verify via surefire-reports timestamps
- Owner-dependent tests fail
- `.test-order-state` records the failure(s) with correct class names
- Subsequent order-mode run (after fix) still boosts previously-failed tests via failure recency

#### F1.4 — JaCoCo coexistence
Verify both agents work simultaneously (JaCoCo is already in petclinic's pom):
```
mvn test -Dtestorder.mode=learn -Djacoco.skip=false
```
**Assert:**
- No `ClassNotFoundException`, `VerifyError`, or `LinkageError`
- JaCoCo report generated (`target/site/jacoco/index.html` or `jacoco.xml`)
- test-order index generated
- Test duration overhead < 2x baseline (measured vs. plain `mvn test`)

#### F1.5 — Select mode in CI simulation
```
mvn test-order:select -Dtestorder.changeMode=uncommitted
mvn test -Dtestorder.mode=order
mvn test-order:run-remaining
```
**Assert:**
- `target/test-order-selected.txt` contains subset of test classes
- `target/test-order-remaining.txt` contains the rest
- Union of selected + remaining = all test classes
- Both runs pass

#### F1.6 — Combined mode (local dev workflow)
```
mvn test -Dtestorder.mode=combined
```
**Assert:**
- Index produced (if first run), or reused (subsequent)
- State file updated with latest run

#### F1.7 — Explicit change mode
```
mvn test -Dtestorder.mode=order \
  -Dtestorder.changeMode=explicit \
  -Dtestorder.changed.classes=org.springframework.samples.petclinic.owner.Owner
```
**Assert:**
- OwnerControllerTests, PetControllerTests, VisitControllerTests boosted
- `show-order` output lists these in top positions

#### F1.8 — Show-order and dump commands
```
mvn test-order:show-order -Dtestorder.changeMode=explicit \
  -Dtestorder.changed.classes=org.springframework.samples.petclinic.owner.Owner
mvn test-order:dump
```
**Assert:**
- `show-order` prints ranked list with scores
- `dump` prints dependency index in human-readable format
- Neither command runs tests

---

## F2 — langchain4j-core (Maven, Large Scale)

**Source:** `langchain4j/langchain4j-core/` (164 test files, 26 using @ParameterizedTest, ~500+ test methods)

**Why this project:** Exercises large dependency graphs (many utility classes, deep call chains), parameterized tests, and select-mode under realistic load. The 164-class scale stresses scoring, set-cover, and index I/O in ways the 2-class IT fixtures cannot.

### Setup

1. Copy `langchain4j/langchain4j-core/` to `test-fixtures/langchain4j-core/`
2. Exclude integration tests (`*IT.java`) that need API keys
3. Add test-order-maven-plugin with `mode=auto`
4. Pin surefire version to 3.2.5

### Test Scenarios

#### F2.1 — Learn mode at scale
```
mvn test -Dtestorder.mode=learn -Dtest='!**/*IT'
```
**Assert:**
- Index file > 10 KB (164 test classes ⇒ substantial dependency graph)
- All `.deps` files contain > 0 dependencies
- Agent doesn't cause > 50% test-time overhead vs. baseline

#### F2.2 — Parameterized test handling
```
mvn test -Dtestorder.mode=order
```
**Assert:**
- Classes with `@ParameterizedTest` appear in dependency index
- Duration EMA is recorded per test class (not per parameterized invocation)
- No duplicate class entries in state file

#### F2.3 — Select mode with many candidates
Inject a change in a widely-depended utility (e.g., `dev.langchain4j.internal.Utils`):
```
mvn test-order:select -Dtestorder.changeMode=explicit \
  -Dtestorder.changed.classes=dev.langchain4j.internal.Utils
```
**Assert:**
- Selected set contains tests with highest dependency overlap on `Utils`
- `selected.txt` is a proper subset (< total test count)
- `remaining.txt` + `selected.txt` = all test classes

#### F2.4 — Optimize with run history
After 5+ learn/order cycles:
```
mvn test-order:optimize
```
**Assert:**
- Optimization completes without error
- Weights in `.test-order-state` have changed from defaults
- Re-running `show-order` reflects new scoring

#### F2.5 — Index round-trip fidelity
```
mvn test-order:dump > dump1.txt
# Re-aggregate from .deps files
mvn test-order:aggregate
mvn test-order:dump > dump2.txt
diff dump1.txt dump2.txt
```
**Assert:**
- Dump output is identical (aggregate is deterministic)

---

## F3 — StarRocks FE Subset (Gradle, Performance)

**Source:** `starrocks/fe/fe-core/` (1,658 test files, Gradle build)

**Why this project:** The Gradle plugin is undertested. StarRocks FE is a massive Java codebase (1000s of source classes) that will stress the agent's instrumentation throughput, index compression, and Gradle daemon memory behavior.

### Setup

1. Create `test-fixtures/starrocks-fe-subset/` as a standalone Gradle project
2. Copy ~50 curated test classes from `starrocks/fe/fe-core/src/test/` with diverse dependency patterns:
   - SQL analyzer tests (deep call chains through parser/analyzer/planner)
   - Catalog tests (database metadata, mock-heavy)
   - Utility tests (lightweight, fast)
3. Copy required source classes (transitive closure of test dependencies)
4. Apply `me.bechberger.test-order` Gradle plugin in `build.gradle`
5. Configure: `testOrder { mode = "auto" }`

### Test Scenarios

#### F3.1 — Gradle learn mode
```
./gradlew test -Dtestorder.mode=learn
```
**Assert:**
- `test-dependencies.lz4` created
- All 50 tests pass
- No Gradle daemon errors in `daemon.log`

#### F3.2 — Gradle order mode with changes
Modify a widely-used source class, then:
```
./gradlew test -Dtestorder.mode=order -Dtestorder.changeMode=uncommitted
```
**Assert:**
- Tests depending on changed class run first
- Verify via test execution timestamps in JUnit XML reports

#### F3.3 — Gradle daemon memory (leak detection)
Run 5 consecutive test invocations without stopping the daemon:
```
for i in {1..5}; do ./gradlew test -Dtestorder.mode=order; done
```
**Assert:**
- Daemon heap usage doesn't grow monotonically (check via `jcmd <pid> GC.heap_info`)
- Each run uses fresh state (not stale from previous run)
- No `OutOfMemoryError` in daemon log

#### F3.4 — Agent overhead at scale
Compare with and without the agent:
```
time ./gradlew test                          # baseline
time ./gradlew test -Dtestorder.mode=learn   # with agent
```
**Assert:**
- Agent overhead < 100% of baseline (target: < 50%)
- No test failures introduced by instrumentation

#### F3.5 — Large index I/O performance
After learn mode produces the index, measure load time:
```
time ./gradlew testOrderShowOrder
```
**Assert:**
- Index loads in < 5 seconds for 50-class fixture
- `show-order` completes and prints all 50 test classes with scores

---

## F4 — Multi-Module Spring (Maven, ReactorContext)

**Source:** `spring-ai/` — extract 3 modules: `spring-ai-commons`, `spring-ai-model`, `spring-ai-client-chat`

**Why this project:** ReactorContext and cross-module state sharing are barely tested. A real 3-module reactor build will reveal state collision bugs, argLine leaks, and state-path redirection issues.

### Setup

1. Create `test-fixtures/multi-module-spring/` with:
   - Parent POM defining test-order-maven-plugin in `<pluginManagement>`
   - `commons/` — module with unit tests (readers, splitters, document model)
   - `model/` — module with tests depending on commons classes
   - `client/` — module with tests depending on both commons and model
2. Source files extracted from `spring-ai/spring-ai-commons/`, `spring-ai-model/`, `spring-ai-client-chat/`
3. Each child module adds `<plugin>` with `<mode>auto</mode>`

### Test Scenarios

#### F4.1 — Reactor learn mode
```
mvn test -Dtestorder.mode=learn
```
**Assert:**
- Each module produces its own `target/test-order-deps/*.deps`
- Shared `.test-order/` directory contains one aggregated state
- `ReactorContext` redirects all modules to the same state path

#### F4.2 — Cross-module change detection
Modify a class in `commons/`, then:
```
mvn test -Dtestorder.mode=order -Dtestorder.changeMode=uncommitted
```
**Assert:**
- `model/` and `client/` test classes that depend on the changed commons class are boosted
- `commons/` tests whose source was directly modified are boosted even higher
- Verify via `show-order` in each module

#### F4.3 — No argLine leak between modules
After running learn mode:
```
mvn test -Dtestorder.mode=learn 2>&1 | grep "argLine"
```
**Assert:**
- Each module's argLine references its own agent path, not a sibling module's
- No absolute paths from module A appear in module B's Surefire fork configuration

#### F4.4 — Aggregate across modules
```
mvn test-order:aggregate
```
**Assert:**
- Aggregated `test-dependencies.lz4` contains classes from all 3 modules
- `test-order:dump` output references source classes from commons, model, and client

#### F4.5 — Snapshot and since-last-run detection
```
mvn test -Dtestorder.mode=order -Dtestorder.changeMode=since-last-run
# Change a file in model/ only
mvn test -Dtestorder.mode=order -Dtestorder.changeMode=since-last-run
```
**Assert:**
- First run: no changed files detected (fresh snapshot)
- Second run: only model/ changes detected
- commons/ and client/ test ordering is unaffected

---

## F5 — Petclinic Gradle Port (Gradle Plugin Parity)

**Source:** `spring-petclinic/` ported to Gradle

**Why this project:** Validates that every Maven workflow also works in Gradle. Using the same project (petclinic) means results are directly comparable.

### Setup

1. Create `test-fixtures/petclinic-gradle/` as a Gradle project
2. Port petclinic's dependencies, source, and tests to `build.gradle`
3. Apply `me.bechberger.test-order` plugin
4. Keep Spring Boot contexts, slices, JaCoCo

### Test Scenarios

#### F5.1 — Gradle learn mode
```
./gradlew test -Dtestorder.mode=learn
```
**Assert:**
- Same set of `.deps` files as Maven F1.1
- Index size comparable to Maven fixture

#### F5.2 — Gradle order mode with bug injection
Same bug injection as F1.3 (Owner.getAddress):
```
./gradlew test -Dtestorder.mode=order -Dtestorder.changeMode=uncommitted || true
```
**Assert:**
- OwnerControllerTests runs first (same as Maven)
- Failures recorded in state file

#### F5.3 — Gradle combined mode
```
./gradlew test -Dtestorder.mode=combined
```
**Assert:**
- Workflow completes (learn + order in one invocation)
- State file updated

#### F5.4 — Gradle show-order task
```
./gradlew testOrderShowOrder
```
**Assert:**
- Prints scored test list
- Same top-ranked tests as Maven `show-order` for equivalent change set

---

## Cross-Cutting Scenarios (run across fixtures)

### C1 — State file corruption recovery
For each fixture, corrupt `.test-order-state` (truncate to 50 bytes), then run order mode.
**Assert:** Recovers gracefully — warns and starts fresh, doesn't crash.

### C2 — Missing index file
Delete `test-dependencies.lz4`, run order mode.
**Assert:** Falls back to learn mode (auto mode) or warns with no reordering (explicit order mode).

### C3 — Empty git history (shallow clone)
```
git clone --depth=1 <fixture-repo-url> shallow-clone/
cd shallow-clone && mvn test -Dtestorder.mode=order -Dtestorder.changeMode=since-last-commit
```
**Assert:** Falls back to all-files-changed (once issue #49 is fixed) or runs without reordering.

### C4 — Repeated runs converge
Run learn → order 5 times on F1. Compare APFD from `.test-order-state` across runs.
**Assert:** APFD is non-decreasing (or stable) — ordering doesn't get worse with more data.

### C5 — Weights file override
Create `custom-weights.toml` with extreme weights (newTest=100, all others=0).
Run order mode with `-Dtestorder.weights.file=custom-weights.toml`.
**Assert:** Brand-new test classes appear first in `show-order` output.

### C6 — No source changes detected via hash mode
Run two consecutive order-mode runs with `since-last-run` without touching any files.
**Assert:** Second run detects 0 changed classes. Ordering is based solely on failure/duration history.

### C7 — First run with hash mode (no previous snapshot)
Run order mode with `since-last-run` on a project that never ran `snapshot` before.
**Assert:** All files treated as changed (correct first-run behavior), not silently empty.

### C8 — Corrupt index file (truncated LZ4)
Truncate `test-dependencies.lz4` to 20 bytes, then run order mode.
**Assert:** Fails with clear error message referencing the corrupt file, doesn't NPE or produce wrong ordering.

### C9 — Cross-platform path roundtrip (Windows paths in Unix CI)
Create a `.test-order-hashes.lz4` file containing backslash-separated paths, then load on Unix.
**Assert:** Paths are normalized — change detection still works.

### C10 — Empty test suite
Configure a module with no test classes, then run learn mode and order mode.
**Assert:** Both modes complete without error. State file has zero durations. No NPE from empty median calculation.

### C11 — All tests are new (no index, no history)
Run order mode on a project with no prior index and no `.test-order-state`.
**Assert:** All tests get `newTest` bonus. Ordering is by new-test score (all equal), then alphabetical.

---

## Edge Cases (unit test level)

Concrete boundary conditions found by source-code audit. Each should become a unit test targeting the specific class. Grouped by severity.

### Critical — Data loss or silent wrong results

| ID | Class | Condition | What happens | Test to write |
|----|-------|-----------|-------------|---------------|
| E1 | `TestScorer` | `medianDuration = 0` (all durations unknown) | ✅ Covered by `allDurationsUnknownDisablesSpeedScoring()` in `DepsAndScoringTest` (verifies `isFast=false`, `isSlow=false`, `speedRatio=0.0`, and `score=0` for every test when no durations are recorded). Not a bug — intentional guard against division by zero. | `allDurationsUnknownDisablesSpeedScoring()` |
| E2 | `TestOrderState` | `optimize()` called when `withFailures.size() == minTrainSize` | ✅ Not a bug — `useExpandingWindow` guard prevents the zero-folds path when `size < 5`. Existing `optimizeReturnsNullWithInsufficientData()` and `optimizeReturnsWeightsWithSufficientData()` in `TestOrderStateTest` cover the two optimize paths. | `optimizeReturnsNullWithInsufficientData()` |
| E3 | `TestOrderState` | `EMA alpha = 0` | ✅ Not a bug — `setDurationAlpha(0)` is validated in range [0,1]. Covered by `durationEmaAlphaZeroNeverUpdates()` in `TestOrderStateTest` (verifies value never updates past initial). | `durationEmaAlphaZeroNeverUpdates()` |
| E4 | `TestOrderState` | `EMA alpha = 1` | ✅ Not a bug — alpha=1 means only the latest sample is kept. Covered by `durationEmaAlphaOneOnlyLatestSample()` in `TestOrderStateTest` (verifies only latest measurement retained). | `durationEmaAlphaOneOnlyLatestSample()` |
| E5 | `TestOrderState` | Failure score just above prune threshold, then one more decay drops it below | ✅ **Bug fixed**: pruning now logs at `FINE` level via `LOG.fine()` when entries are dropped. Covered by `failureScorePruningIsLogged()` in `DepsAndScoringTest` (captures log handler, verifies message text). Existing `failureScorePrunedWhenBelowThreshold()` validates the pruning mechanics. | `failureScorePruningIsLogged()` |
| E6 | `TestOrderState` | Concurrent threads: orderer writes `pendingRunData`, listener reads it simultaneously | ✅ Covered by `pendingStateReadWhileConcurrentWritesIsConsistent()` in `TestOrderStateTest` (read/write snapshot consistency + no partial breakdowns) | `pendingStateReadWhileConcurrentWritesIsConsistent()` |
| E7 | `TestOrderState` | `computeAPFD()` with `m=0` (no failures) AND `n=0` (no tests) | ✅ Not a bug — returns 1.0 for empty/no-failure inputs (intentional). Covered by `apfdEmptyOutcomesIsOne()` and `apfdNoFailuresIsOne()` in `TestOrderStateTest`. | `apfdEmptyOutcomesIsOne()`, `apfdNoFailuresIsOne()` |
| E8 | `TelemetryListener` | Same test class executed twice (e.g. `@RepeatedTest`, Surefire re-run on failure) | ✅ Covered by `repeatedClassExecutionDoesNotAccumulatePreviousRunDuration()` in `TelemetryListenerTest` (verifies durations are per execution, not cumulative). | `repeatedClassExecutionDoesNotAccumulatePreviousRunDuration()` |
| E9 | `CombinedMojo` | Optimization trigger should be based on run counter, not run-history size/thinning artifacts | ✅ Covered by `optimizationTriggerUsesRunsSinceLearnCounter()` in `CombinedMojoTest` (asserts optimize fires exactly at `runsSinceLearn % optimizeEvery == 0`). | `optimizationTriggerUsesRunsSinceLearnCounter()` |
| E10 | `DependencyMap` | Row-deduplicated tests share same `HashSet` instance; caller mutates returned set | ✅ Covered by `rowDeduplicatedSetsAreImmutable()` and `rowDeduplicatedSetsReturnedByGetAreImmutable()` in `DependencyMapTest` (returned sets are unmodifiable, preventing shared-row corruption). | `rowDeduplicatedSetsAreImmutable()` |

### High — Incorrect ordering or selection

| ID | Class | Condition | What happens | Test to write |
|----|-------|-----------|-------------|---------------|
| E11 | `TestSelector` | `topN > total test count` | ✅ Covered by `topNLargerThanTestCountSelectsAll()` in `TestSelectorTest`. | `topNLargerThanTestCountSelectsAll()` |
| E12 | `TestSelector` | `randomM > available fast tests` | ✅ Covered by `randomMSelectsFastDiverseTests()` in `TestSelectorTest`. | `randomMSelectsFastDiverseTests()` |
| E13 | `TestSelector` | All tests are new (`isNew=true`) | ✅ Covered by `newTestsAlwaysSelected()` in `TestSelectorTest`. | `newTestsAlwaysSelected()` |
| E14 | `TestSelector` | Input is empty (zero test classes) | ✅ Covered by `emptyDepMapSelectsNothing()` in `TestSelectorTest`. | `emptyDepMapSelectsNothing()` |
| E15 | `TestSelector` | Verify `selected ∪ remaining = total` after every selection | ✅ Covered by `selectedAndRemainingAreDisjointAndComplete()` in `TestSelectorTest`. | `selectedAndRemainingAreDisjointAndComplete()` |
| E16 | `SetCoverComputer` | Empty universe | ✅ Covered by `emptyUniverseReturnsEmptyResult()` in `SetCoverComputerTest`. | `emptyUniverseReturnsEmptyResult()` |
| E17 | `SetCoverComputer` | Empty coverage (no tests map to any dep) | ✅ Covered by `emptyCoverageReturnsEmptyResult()` in `SetCoverComputerTest`. | `emptyCoverageReturnsEmptyResult()` |
| E18 | `SetCoverComputer` | Single test covers entire universe | ✅ Covered by `singleTestCoversEntireUniverse()` in `SetCoverComputerTest`. | `singleTestCoversEntireUniverse()` |
| E19 | `SetCoverComputer` | All tests have identical dep sets | ✅ Covered by `allTestsIdenticalDepSetsUniformInitialCounts()` in `SetCoverComputerTest`. | `allTestsIdenticalDepSetsUniformInitialCounts()` |
| E20 | `SetCoverComputer` | Stale QueueEntry count after re-queue | ✅ Covered by `staleQueueEntryCountSkipped()` in `SetCoverComputerTest`. | `staleQueueEntryCountSkipped()` |
| E21 | `TestScorer` | `complexityScore()` with 0 depTotal | ✅ Covered by `complexityScoreFormula()` in `DepsAndScoringTest` (includes `TestScorer.complexityScore(0.0, 0, 2) == 0`, guarding zero-dep-total path). | `complexityScoreFormula()` |
| E22 | `TestScorer` | Set-cover bonus with empty changedClasses | ✅ Covered by `setCoverBonusSkippedWhenChangedClassesEmpty()` in `DepsAndScoringTest` (coverage bonus remains 0 when changed-classes set is empty). | `setCoverBonusSkippedWhenChangedClassesEmpty()` |
| E23 | `PriorityClassOrderer` | Test class is a deeply nested inner class (`A$B$C$D`) | ✅ Covered by `deepNestedInnerClassDescriptorResolvesToTopLevelName()` in `PriorityClassOrdererTest` (nested descriptor resolves via top-level class for dep-map lookup). | `deepNestedInnerClassDescriptorResolvesToTopLevelName()` |
| E24 | `PriorityClassOrderer` | `testorder.index.path` not set (null) | ✅ Covered by `noIndexPathDoesNotReorder()` in `PriorityClassOrdererTest` (silent no-op with preserved original order). | `noIndexPathDoesNotReorder()` |
| E25 | `PriorityMethodOrderer` | `orderMethods()` with empty method list | ✅ Covered by `orderMethods_emptyMethodList_noOp()` in `PriorityMethodOrdererTest` (returns without error and keeps list empty). | `orderMethods_emptyMethodList_noOp()` |
| E26 | `PriorityMethodOrderer` | All methods have the same score | ✅ Covered by `orderMethods_allTied_preservesSourceOrder()` in `PriorityMethodOrdererTest` (tie-breaking preserves source order). | `orderMethods_allTied_preservesSourceOrder()` |
| E27 | `PriorityMethodOrderer` | No pending state set (telemetry unavailable) | ✅ Covered by `orderMethods_noPendingState_defaultOrder()` in `PriorityMethodOrdererTest` (falls back to default ordering). | `orderMethods_noPendingState_defaultOrder()` |
| E28 | `ChangeDetector` | `mode=null` or `mode=""` | ✅ Covered by `modeParseDefaultsToSinceLastRun()` in `ChangeDetectorTest` (null/empty/blank parse to `SINCE_LAST_RUN`). | `modeParseDefaultsToSinceLastRun()` |
| E29 | `ChangeDetector` | Shallow clone / single-commit repo where `HEAD~1` doesn't exist | ✅ Covered by `sinceLastCommitWithSingleCommitRepoFallsBackGracefully()` in `ChangeDetectorTest` (no throw on missing `HEAD~1`). | `sinceLastCommitWithSingleCommitRepoFallsBackGracefully()` |

### Medium — I/O, path, and config edge cases

| ID | Class | Condition | What happens | Test to write |
|----|-------|-----------|-------------|---------------|
| E30 | `DependencyMap` | Corrupted V4 header: `readInt()` returns negative | ✅ Not a bug — `checkSize()` at line 410 validates `size < 0 || size > MAX_BLOCK_SIZE`. Covered by `loadTruncatedBinaryFileThrowsIOException()` and `loadCompletelyGarbageBinaryFileThrowsIOException()` in `DependencyMapTest`. | `loadTruncatedBinaryFileThrowsIOException()` |
| E31 | `DependencyMap` | Index file > `MAX_COMPRESSED_FILE_SIZE` (1 GB) | ✅ Not a bug — `validateCompressedFileSize()` checks before decompression. Covered by `compressedIndexSizeGuardRejectsHugeFiles()` in `DependencyMapTest`. | `compressedIndexSizeGuardRejectsHugeFiles()` |
| E32 | `DependencyMap` | Empty `.deps` directory (no `.deps` files) | ✅ Not a bug — `Files.list()` returns empty stream, aggregate returns empty map. Covered by `aggregateEmptyDirReturnsSizeZero()` in `DependencyMapTest`. | `aggregateEmptyDirReturnsSizeZero()` |
| E33 | `FileHashStore` | Save on Windows (`\` separator), load on Unix (`/`) | ✅ **Bug fixed**: `load()` now normalizes backslash keys to forward slashes via `replace('\\', '/')`. Covered by `loadNormalizesBackslashKeysToForwardSlash()` in `FileHashStoreTest` (writes backslash keys, verifies normalization on load, and cross-platform `getChangedFiles()` comparison). | `loadNormalizesBackslashKeysToForwardSlash()` |
| E34 | `FileHashStore` | Source root doesn't exist | ✅ Not a bug — `scan()` checks `Files.isDirectory()` first, returns empty store. Covered by `nonExistentSourceRoot()` in `FileHashStoreTest`. | `nonExistentSourceRoot()` |
| E35 | `FileHashStore` | Symlink cycle in directory tree | ✅ Not a bug — `Files.walk()` defaults to `NOFOLLOW_LINKS`, so symlink cycles cannot cause infinite loops. Not unit-testable portably. | N/A (safe by default) |
| E36 | `FileHashStore` | Corrupted/truncated LZ4 file on load | ✅ Covered by `loadTruncatedLz4FileThrowsIOException()` and `loadCompletelyRandomBytesThrowsIOException()` in `FileHashStoreTest`. | `loadTruncatedLz4FileThrowsIOException()` |
| E37 | `MethodHashStore` | `scan()` with file that JavaParser cannot parse | ✅ Covered by `scanIgnoresNonSourceFiles()` and `sourceFileWithoutMethods()` in `MethodHashStoreTest`. | `scanIgnoresNonSourceFiles()` |
| E38 | `MethodHashStore` | Save/load round-trip with special characters in method name | ✅ Covered by `specialCharactersInMethodNames()` in `MethodHashStoreTest`. | `specialCharactersInMethodNames()` |
| E39 | `GitChangeDetector` | `git diff` takes > 30 seconds | ✅ Not a bug — `runGit()` has explicit `GIT_TIMEOUT_SECONDS=30` with `destroyForcibly()` and empty-list return. Not unit-testable without process mocking. | N/A (timeout handling verified by code review) |
| E40 | `GitChangeDetector` | Git exit code non-zero (corrupted repo) | ✅ Not a bug — `runGit()` checks `exitValue() != 0` and throws `IOException` with stderr. Not unit-testable without process mocking. | N/A (exit-code check verified by code review) |
| E41 | `LineDiff` | Both inputs null | ✅ Covered by `bothNullReturnsZeroChanges()` in `LineDiffTest`. | `bothNullReturnsZeroChanges()` |
| E42 | `LineDiff` | Both inputs identical | ✅ Covered by `singleLineIdenticalReturnsZero()` and `multiLineIdenticalReturnsZero()` in `LineDiffTest`. | `singleLineIdenticalReturnsZero()` |
| E43 | `LineDiff` | One input empty, other has 100 lines | ✅ Covered by `emptyTo100LinesAllChanged()` and `hundredLinesToEmptyAllChanged()` in `LineDiffTest`. | `emptyTo100LinesAllChanged()` |
| E44 | `LineDiff` | Single-line files with one character difference | ✅ Covered by `singleCharDifferenceReturnsTwo()` in `LineDiffTest`. | `singleCharDifferenceReturnsTwo()` |
| E45 | `ChangeComplexity` | Inner class FQCN (`com.Foo$Bar`) | ✅ Covered by `innerClassFqcnResolved()` in `ChangeComplexityTest`. | `innerClassFqcnResolved()` |
| E46 | `ChangeComplexity` | `serialise()`/`deserialise()` round-trip | ✅ Covered by nested `SerialiseRoundTrip` class in `ChangeComplexityTest` (`emptyMapRoundTrip()`, `singleEntryRoundTrip()`, `nullBlankDeserialised()`). | `SerialiseRoundTrip.*` |
| E47 | `ChangeComplexity` | All changed classes have zero complexity delta | ✅ Covered by `allZeroReturnsEmpty()` in `ChangeComplexityTest.FromRawSizes` nested class. | `allZeroReturnsEmpty()` |
| E48 | `StructuralChangeAnalyzer` | Diff contains `<clinit>` (static initializer) change | ✅ Covered by `staticInitializerChangeDetected()` in `StructuralChangeAnalyzerTest`. | `staticInitializerChangeDetected()` |
| E49 | `StructuralChangeAnalyzer` | Empty diffs list | ✅ Covered by `emptyDiffsReturnsEmpty()` in `StructuralChangeAnalyzerTest`. | `emptyDiffsReturnsEmpty()` |
| E50 | `StructuralChangeAnalyzer` | Type-level change (class renamed, superclass changed) | ✅ Covered by `typeLevelChangeMarksTypeChanged()` in `StructuralChangeAnalyzerTest`. | `typeLevelChangeMarksTypeChanged()` |
| E51 | `PersistenceSupport` | `ATOMIC_MOVE` fails (FAT32, some network mounts) | ✅ `moveIntoPlace()` handles `AtomicMoveNotSupportedException` fallback. Covered by `moveIntoPlaceReplacesTargetAndRemovesTemp()` in `PersistenceSupportTest`. | `moveIntoPlaceReplacesTargetAndRemovesTemp()` |
| E52 | `PersistenceSupport` | Neither target nor temp file exists | ✅ Covered by `resolveLoadPathReturnsTargetWhenNeitherExists()` in `PersistenceSupportTest`. | `resolveLoadPathReturnsTargetWhenNeitherExists()` |
| E53 | `TelemetryListener` | Agent reflection fails (`NoSuchMethodException` — version mismatch) | ✅ Covered by `agentUnavailableInLearnModeStillPersistsFailures()` and `agentUnavailableDoesNotPreventDurationRecordingInOrderMode()` in `TelemetryListenerTest` (graceful degradation with persisted state). | `agentUnavailableInLearnModeStillPersistsFailures()` |
| E54 | `TelemetryListener` | `@Execution(CONCURRENT)` on test classes | ✅ Covered by `warnsWhenTestClassUsesConcurrentExecution()` in `TelemetryListenerTest` (explicit warning emitted). | `warnsWhenTestClassUsesConcurrentExecution()` |
| E55 | `PackageDetector` | Deeply-nested single-child chain: `a/b/c/d/e/Foo.java` | ✅ Covered by `detectSourcePackagesFindsDeepSingleChildChain()` in `PackageDetectorTest`. | `detectSourcePackagesFindsDeepSingleChildChain()` |
| E56 | `PackageDetector` | `minimisePrefixes(["com.foo", "com.foo.bar"])` | ✅ Covered by `minimisePrefixesRemovesSubsumedAndDuplicateEntries()` in `PackageDetectorTest`. | `minimisePrefixesRemovesSubsumedAndDuplicateEntries()` |
| E57 | `Agent` | Invalid `agentArgs` string (malformed options) | ✅ Covered by `parseInvalidArgsFailsFast()` in `AgentTest`. | `parseInvalidArgsFailsFast()` |
| E58 | `AgentLogger` | `setVerboseFile()` with path that doesn't exist (parent dir missing) | ✅ Covered by `missingDirIsHandledGracefully()` in `AgentLoggerTest`. | `missingDirIsHandledGracefully()` |

---

## Automation

### JUnit integration suites

Use the existing JUnit integration suites as the automation backbone:

- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/it/AbstractEndToEndIT.java`
- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/it/AdvancedWorkflowIT.java`
- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/it/BugVerificationIT.java`
- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/it/EndToEndServiceIT.java`
- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/it/UserPerspectiveIT.java`
- `test-order-maven-plugin/src/test/java/me/bechberger/testorder/plugin/MavenPluginIT.java`

Add new scenarios to these suites (or adjacent JUnit classes) instead of introducing a separate Python harness.

### CI integration

Add to `.github/workflows/ci.yml`:

```yaml
fixture-tests:
  needs: build-and-test
  runs-on: ubuntu-latest
  timeout-minutes: 45
  strategy:
    matrix:
      java: [17, 21]
      fixture: [petclinic, langchain4j-core, multi-module-spring]
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { java-version: '${{ matrix.java }}', distribution: temurin }
    - name: Install test-order
      run: mvn -B -ntp -DskipTests install
    - name: Run fixture tests
      run: mvn -B -ntp -pl test-order-maven-plugin -Dtest='*IT,*MavenPluginIT' test
    - name: Upload reports
      if: failure()
      uses: actions/upload-artifact@v4
      with:
        name: fixture-${{ matrix.fixture }}-java${{ matrix.java }}
        path: test-fixtures/${{ matrix.fixture }}/**/surefire-reports/
```

Gradle fixtures run in a separate job (needs Gradle setup):

```yaml
gradle-fixture-tests:
  needs: build-and-test
  runs-on: ubuntu-latest
  timeout-minutes: 30
  strategy:
    matrix:
      java: [17, 21]
      fixture: [starrocks-fe-subset, petclinic-gradle]
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { java-version: '${{ matrix.java }}', distribution: temurin }
    - name: Install test-order (Maven artifacts for Gradle consumption)
      run: mvn -B -ntp -DskipTests install
    - name: Run fixture tests
      run: ./gradlew :test-order-gradle-plugin:test
```

---

---

## Verification Checklist Per Fixture

| Check | F1 | F2 | F3 | F4 | F5 |
|-------|----|----|----|----|-----|
| Learn mode produces index | ✓ | ✓ | ✓ | ✓ | ✓ |
| Order mode reorders correctly | ✓ | ✓ | ✓ | ✓ | ✓ |
| Select + run-remaining covers all tests | ✓ | ✓ | | | |
| Change detection works (git/hash/explicit) | ✓ | ✓ | ✓ | ✓ | ✓ |
| State file round-trips cleanly | ✓ | ✓ | ✓ | ✓ | ✓ |
| JaCoCo coexistence | ✓ | | | | ✓ |
| @ParameterizedTest handled | | ✓ | | | |
| Spring context slices work | ✓ | | | ✓ | ✓ |
| Multi-module reactor | | | | ✓ | |
| Gradle plugin | | | ✓ | | ✓ |
| Daemon memory stability | | | ✓ | | |
| Agent overhead measured | ✓ | ✓ | ✓ | | |
| Corruption recovery | ✓ | ✓ | ✓ | ✓ | ✓ |
| Empty test suite | | | | ✓ | |
| Cross-platform path roundtrip | ✓ | ✓ | | ✓ | |
| All-tests-new ordering | ✓ | | | | ✓ |

---

## Test Count Summary

| Section | Count |
|---------|------:|
| Missing coverage gaps (Tier 1–3) | 33 classes |
| Fixture scenarios (F1–F5) | 23 scenarios |
| Cross-cutting scenarios (C1–C11) | 11 scenarios |
| Edge cases (E1–E58) | 58 cases |
| **Total verifiable items** | **125** |

---

## Priority Order for Implementation

1. **F1 (petclinic)** — easiest to set up (demo script already exists), covers the widest scenario spread
2. **F4 (multi-module-spring)** — ReactorContext is a known weak spot, high-value validation
3. **F2 (langchain4j-core)** — large-scale stress test, exercises parameterized tests
4. **F3 (starrocks-fe-subset)** — Gradle plugin validation, performance measurement
5. **F5 (petclinic-gradle)** — Maven/Gradle parity check, do after F3 proves Gradle plugin works

