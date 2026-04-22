# test-order — Test Coverage & Bug Audit

**Generated:** 2026-04-20  
**Sources:** TEST_PLAN.md, BUGS.md, BUG_TEST_RESULTS.md, IMPROVEMENT_PLAN.md, structural-diff-integration-plan.md, plan.md, actual source & test inventory, CI configuration

---

## 1. Executive Summary

| Category | Documented | Verified | Gap |
|----------|-----------|----------|-----|
| Unit test classes (Tier 1–3) | 33 classes | 33/33 ✅ | 0 |
| Edge case tests (E1–E58) | 58 cases | 58/58 ✅ | 0 |
| Test fixture projects (F1–F5) | 5 fixtures | **0/5** ❌ | 5 |
| Fixture scenarios (F1.1–F5.4) | 27 scenarios | **0/27** ❌ | 27 |
| Cross-cutting scenarios (C1–C11) | 11 scenarios | **0/11** ❌ | 11 |
| Bugs in BUGS.md | 88 + B0 | See §3 | Mixed |
| BUGS.md entries actually fixed in code | — | **≥12** | BUGS.md stale |
| Structural-diff integration tests | 3 tiers planned | **0** | 3 |
| CI jobs | 4 jobs | 4/4 ✅ | Configuration gaps remain |

**Bottom line:** Unit-level coverage is strong — all 33 gap classes and 58 edge cases are addressed with tests. However:

1. **No test fixture projects** from the TEST_PLAN have been created (the `test-fixtures/` directory does not exist).
2. **BUGS.md is significantly stale** — at least 12 bugs are marked "Open" but have verified code fixes.
3. **38 integration/cross-cutting scenarios** exist only on paper.
4. **Bug B0** (member-level scoring) remains the single confirmed functional bug with no fix.

---

## 2. Test File Inventory (Actual State)

### 2.1 — Test classes by module

| Module | Unit Tests | Integration Tests | Total |
|--------|-----------|-------------------|-------|
| test-order-core | 21 | 0 | 21 |
| test-order-junit | 3 | 0 | 3 |
| test-order-agent | 9 | 0 | 9 |
| test-order-maven-plugin | 13 | 10 | 23 |
| test-order-gradle-plugin | 4 | 2 | 6 |
| **Total** | **50** | **12** | **62** |

Plus 10 invoker IT fixtures in `test-order-maven-plugin/src/it/`.

### 2.2 — Core module test files

| Test File | Target Class(es) | Tests (approx) |
|-----------|------------------|----------------|
| `DependencyMapTest` | DependencyMap | 15+ |
| `TestOrderStateTest` | TestOrderState | 57 |
| `StateConfigurationTest` | StateConfiguration | 11 |
| `DurationTrackerTest` | DurationTracker | 3 |
| `RunHistoryManagerTest` | RunHistoryManager | 3 |
| `FailureHistoryTrackerTest` | FailureHistoryTracker | 5 |
| `DepsAndScoringTest` | TestScorer, MethodScorer | 100 |
| `SetCoverComputerTest` | SetCoverComputer | 22 |
| `TestSelectorTest` | TestSelector | 11 |
| `ClassNameTrieTest` | ClassNameTrie | — |
| `PersistenceSupportTest` | PersistenceSupport | 4+ |
| `ToolTest` | Tool | 1+ |
| `ChangeDetectorTest` | ChangeDetector | — |
| `GitChangeDetectorTest` | GitChangeDetector | — |
| `FileHashStoreTest` | FileHashStore | 10+ |
| `MethodHashStoreTest` | MethodHashStore | 39 |
| `LineDiffTest` | LineDiff | 52 |
| `ChangeComplexityTest` | ChangeComplexity | 25+ |
| `StructuralChangeAnalyzerTest` | StructuralChangeAnalyzer | 53 |
| `StructuralDiffTest` | StructuralDiff | — |
| `SourceFileModelTest` | SourceFileModel | — |
| `SourceFileModelUtilTest` | SourceFileModel utils | — |
| `SourceFileModelCrossCheckTest` | SourceFileModel cross-check | — |
| `JavaParserModelTest` | JavaParser model | — |
| `AnnotationMethodDebugTest` | Annotation edge cases | — |

### 2.3 — JUnit module test files

| Test File | Target Class(es) |
|-----------|------------------|
| `PriorityClassOrdererTest` | PriorityClassOrderer |
| `PriorityMethodOrdererTest` | PriorityMethodOrderer |
| `TelemetryListenerTest` | TelemetryListener |

### 2.4 — Agent module test files

| Test File | Target Class(es) |
|-----------|------------------|
| `AgentTest` | Agent |
| `ClassTransformerTest` | ClassTransformer |
| `IntelligentClassFilterTest` | IntelligentClassFilter |
| `ProjectStructureAnalyzerTest` | ProjectStructureAnalyzer |
| `UsageStoreTest` | UsageStore |
| `ClassIdMapTest` | ClassIdMap |
| `BitsetTrackerTest` | BitsetTracker |
| `AgentLoggerTest` | AgentLogger |
| `FieldTrackingTest` | Field tracking E2E |

### 2.5 — Maven plugin test files

**Unit tests:** `AbstractTestOrderMojoTest`, `PrepareMojoTest`, `CombinedMojoTest`, `SelectMojoTest`, `OptimizeMojoTest`, `ShowOrderMojoTest`, `RunRemainingMojoTest`, `DumpMojoTest`, `AggregateMojoTest`, `SnapshotMojoTest`, `ChangeDetectionHelperTest`, `ReactorContextTest`, `SurefireHelperTest`

**Integration tests:** `AbstractEndToEndIT`, `EndToEndJUnit5IT`, `EndToEndJUnit6IT`, `EndToEndServiceIT`, `AdvancedWorkflowIT`, `FieldsMethodsWorkflowIT`, `UserPerspectiveIT`, `UserScenarioIT`, `BugVerificationIT`, `MavenPluginIT`

### 2.6 — Gradle plugin test files

`TestOrderPluginTest`, `TestOrderPluginTaskRegistrationTest`, `TestOrderExtensionTest`, `PackageDetectorTest`, `TestOrderPluginIntegrationTest` (IT), `SpringBootCoreModulesIT` (IT)

---

## 3. BUGS.md Status Audit

### 3.1 — Bugs marked "Open" that are actually fixed in code

Evidence from source code inspection:

| Bug # | BUGS.md Status | Code Evidence | Correct Status |
|-------|---------------|---------------|----------------|
| **#2** | Open | `addRunRecord()` is `synchronized` (TestOrderState.java:628) | **Fixed** |
| **#6** | Open | `schemaVersion` written/validated in save/load (TestOrderState.java:818, 1036–1046) | **Fixed** |
| **#49** | Open | `GIT_TIMEOUT_SECONDS=30` with `destroyForcibly()` (GitChangeDetector.java:18,103–104) | **Partially Fixed** (timeout added; shallow-clone fallback needs verification) |
| **#50** | Open | Same timeout mechanism as #49 | **Fixed** |
| **#51** | Open | All getters return `Collections.unmodifiableSet()` (DependencyMap.java:73,82,98,103,108,125,140,145); shared rows wrapped at line 473 | **Fixed** |
| **#62** | Open | `UsageStore.active` replaced with `InheritableThreadLocal<ActiveTrackers>` (UsageStore.java:72–73) | **Fixed** |
| **#63** | Open | `listFiles()` null-checked: `if (children == null)` (ProjectStructureAnalyzer.java:199–200) | **Fixed** |
| **#64** | Open | `System.nanoTime()` used for all duration tracking (TelemetryListener.java:83,98,302) | **Fixed** |
| **#65** | Open | `isTestClass()` now uses `endsWith("Test")`, `endsWith("Tests")`, `endsWith("TestCase")` (IntelligentClassFilter.java:313–315) | **Fixed** |
| **#69** | Open | `StateSerializer.save()` uses `PersistenceSupport.temporarySibling()` + `moveIntoPlace()` with `ATOMIC_MOVE` (StateSerializer.java:28–32) | **Fixed** |
| **#71** | Open | `FileHashStore` normalizes backslash to forward slash on scan (line 40) and load (line 80) | **Fixed** |
| **#76** | Open ("No unit tests for PriorityMethodOrderer, UsageStore, MethodScorer, or Mojos") | `PriorityMethodOrdererTest`, `UsageStoreTest`, `DepsAndScoringTest` (covers MethodScorer), all Mojo tests exist | **Fixed** |

### 3.2 — Bugs likely fixed but needing deeper verification

| Bug # | Description | Evidence | Needs |
|-------|-------------|----------|-------|
| **#3** | Race between `recordBreakdown()` and `setStatePath()` | `addRunRecord` is synchronized; need to check if pending-state mechanism was refactored | Code review of pending state |
| **#4** | `getPendingStatePath()` releases lock too early | Related to #3; `TestOrderStateTest` has `pendingStateReadWhileConcurrentWritesIsConsistent()` | Verify atomic get-and-reset |
| **#7** | `DumpMojo`/`ShowOrderMojo` use `System.out` | Test exists (`ShowOrderMojoTest`, `DumpMojoTest`) but need to verify `getLog()` usage | Quick code check |
| **#11** | `toInt()`/`toDouble()` crash on malformed state | `safeInt()` method visible in schemaVersion parsing (line 1040); may be applied broadly | Code review |

### 3.3 — P0 bugs confirmed still open

| Bug # | Area | Description | Regression Test Exists? |
|-------|------|-------------|------------------------|
| **B0** | Scoring | Member-level scoring doesn't differentiate tests covering different methods of same class | ❌ No regression test (manual test in BUG_TEST_RESULTS.md only) |
| **#1** | Architecture | `TestOrderState` is a 1,360-line god class | N/A (refactoring task) |
| **#5** | Correctness | Overfit detection uses different train/val split than fitness function | ❌ No regression test |
| **#8** | Release | No Maven artifact rollback on git push failure | N/A (process issue) |

### 3.4 — P1 bugs confirmed still open (selected high-impact)

| Bug # | Area | Description | Regression Test? |
|-------|------|-------------|-----------------|
| **#9** | Error Handling | 20+ broad `catch (Exception e)` blocks | ❌ |
| **#13** | Algorithm | `l2Penalty()` ignores mismatched weights length | ❌ |
| **#14** | Algorithm | No `WeightDef.min <= max` validation | ❌ |
| **#15** | Gradle | 754-line monolith plugin class | N/A (refactoring) |
| **#16** | Gradle | Race conditions in parallel Gradle builds — no file locking | ❌ |
| **#17** | Gradle | Missing 5 of 10 Maven goals | N/A (feature gap) |
| **#20** | Static State | `PriorityMethodOrderer` static fields leak across runs | ❌ |
| **#52** | Telemetry | `putIfAbsent` drops repeated-class durations | ❌ |
| **#66** | Threading | `callEndTestClass()` ends wrong class under `@Execution(CONCURRENT)` | ⚠️ Warning test exists, fix not verified |
| **#67** | Shading | No shade relocations — classpath conflicts | ❌ |
| **#68** | Maven Plugin | `System.setProperty("argLine")` is JVM-global | ❌ |
| **#70** | Agent | `ClassIdMap.computeIfAbsent` returns null on exhaustion | ❌ |

### 3.5 — Summary: BUGS.md needs update

- **12 bugs should be marked Fixed** (§3.1)
- **4 bugs need deeper verification** before status change (§3.2)
- **4 P0 bugs remain genuinely open** (§3.3), of which B0 and #5 are correctness issues
- **12+ P1 bugs remain genuinely open** (§3.4)
- **P2/P3 bugs** — most remain open (documentation, hygiene, performance); see BUGS.md for full list

---

## 4. Test Fixtures — All Missing

TEST_PLAN.md defines 5 test fixture projects under `test-fixtures/`. **This directory does not exist.**

| Fixture | Source | Build | Scenarios | Status |
|---------|--------|-------|-----------|--------|
| **F1** petclinic | spring-petclinic | Maven | 8 (F1.1–F1.8) | ❌ Not created |
| **F2** langchain4j-core | langchain4j | Maven | 5 (F2.1–F2.5) | ❌ Not created |
| **F3** starrocks-fe-subset | starrocks | Gradle | 5 (F3.1–F3.5) | ❌ Not created |
| **F4** multi-module-spring | spring-ai | Maven | 5 (F4.1–F4.5) | ❌ Not created |
| **F5** petclinic-gradle | spring-petclinic | Gradle | 4 (F5.1–F5.4) | ❌ Not created |

### 4.1 — Existing IT fixtures (partial alternatives)

The 10 invoker fixtures in `test-order-maven-plugin/src/it/` partially cover some scenarios:

| IT Fixture | Partially Covers | Key Gaps vs. TEST_PLAN |
|-----------|------------------|----------------------|
| `basic-learn-mode/` | F1.1 (learn) | No Spring, no JaCoCo, only 2 test classes |
| `order-mode/` | F1.2 (order baseline) | No change detection, no Spring |
| `select-mode/` | F1.5 (select) | No change-mode integration |
| `run-remaining-mode/` | F1.5 (run-remaining) | No combined workflow |
| `reactor-learn-mode/` | F4.1 (reactor learn) | Only 2 modules, no cross-module change detection |
| `aggregate-deps/` | F4.4 (aggregate) | Limited assertions |

JUnit 6 variants (`*-junit6/`) mirror the above with JUnit 6 APIs.

### 4.2 — Fixture gaps not covered by any existing test

| Gap | TEST_PLAN Ref | BUGS.md Ref |
|-----|--------------|-------------|
| Spring Boot test slices (`@DataJpaTest`, `@WebMvcTest`, `@SpringBootTest`) | F1.1, F1.4 | #55 |
| JaCoCo coexistence | F1.4 | #54 |
| `@ParameterizedTest` handling | F2.2 | #53 |
| Bug injection / fault localization | F1.3 | — |
| Multi-module cross-module change detection | F4.2 | #68, #80 |
| Gradle daemon memory stability | F3.3 | #95 |
| Performance / overhead measurement | F3.4 | #57 |
| Gradle plugin full workflow | F3, F5 | #17, #19 |
| Kotlin Kotest framework | — | #56 |

---

## 5. Cross-Cutting Scenarios — Status

| ID | Description | Status | Partial Coverage |
|----|-------------|--------|-----------------|
| C1 | State file corruption recovery | ❌ Not implemented | — |
| C2 | Missing index file fallback | ❌ Not implemented | `OptimizeMojoTest` covers missing-state |
| C3 | Empty git history (shallow clone) | ⚠️ Partial | `ChangeDetectorTest.sinceLastCommitWithSingleCommitRepoFallsBackGracefully()` |
| C4 | Repeated runs converge (APFD non-decreasing) | ❌ Not implemented | — |
| C5 | Weights file override | ❌ Not implemented | — |
| C6 | No source changes via hash mode | ❌ Not implemented | — |
| C7 | First run with hash mode (no snapshot) | ❌ Not implemented | — |
| C8 | Corrupt index file (truncated LZ4) | ⚠️ Partial | `DependencyMapTest.loadTruncatedBinaryFileThrowsIOException()` |
| C9 | Cross-platform path roundtrip | ⚠️ Partial | `FileHashStoreTest.loadNormalizesBackslashKeysToForwardSlash()` |
| C10 | Empty test suite | ❌ Not implemented | — |
| C11 | All tests are new (no index, no history) | ⚠️ Partial | `TestSelectorTest.newTestsAlwaysSelected()` |

---

## 6. Structural-Diff Integration — No Tests

Per `structural-diff-integration-plan.md`, three tiers are planned:

| Tier | Feature | Status | Notes |
|------|---------|--------|-------|
| 1 | Structural change weighting (impact scores per class) | ❌ No tests | `ChangeComplexityTest` covers math but not scoring integration |
| 2 | Method-level dependency matching (V3 index) | ❌ No tests | `PriorityMethodOrdererTest` doesn't test structural-diff paths |
| 3 | Member-level dependency tracking (agent changes) | ❌ No tests | Root cause of Bug B0 |

---

## 7. CI Configuration Audit

### 7.1 — Current CI (verified from `.github/workflows/ci.yml`)

| Job | JDK | Timeout | What it runs |
|-----|-----|---------|-------------|
| `build-and-test` | 17, 21 | 30 min | `mvn test` (unit tests) |
| `maven-plugin-integration-tests` | 17 | 30 min | `mvn -Prun-its verify -pl test-order-maven-plugin` |
| `gradle-plugin-tests` | 21 | 30 min | `./gradlew test` in gradle plugin module |
| `end-to-end-tests` | 17 | 30 min | `mvn verify -Dtestorder.it=true -pl test-order-maven-plugin` |

### 7.2 — BUGS.md CI issues vs. reality

| Bug # | Claim | Actual | Status |
|-------|-------|--------|--------|
| **#18** | "JDK matrix covers only JDK 17" | Matrix is `['17', '21']` | **Fixed** — BUGS.md stale |
| **#19** | "Gradle plugin ITs not in CI" | `gradle-plugin-tests` job exists | **Fixed** — BUGS.md stale |
| **#78** | "No `timeout-minutes` on CI jobs" | All 4 jobs have `timeout-minutes: 30` | **Fixed** — BUGS.md stale |
| **#79** | "`-Dtestorder.it=true` not verified" | E2E job verifies test count via Python script | **Fixed** — BUGS.md stale |

### 7.3 — Remaining CI gaps

| Gap | Impact | Effort |
|-----|--------|--------|
| No JaCoCo in CI (BUGS.md #37) | Zero coverage visibility | Medium |
| Gradle ITs don't run `SpringBootCoreModulesIT` in CI (needs `spring-boot/` checkout + JDK 25+) | Heavy Gradle IT not exercised | High |
| No fixture-based tests in CI (no `test-fixtures/` directory) | All fixture scenarios untested | Blocked on fixture creation |

---

## 8. Document Inconsistencies

### 8.1 — BUGS.md entries that should be updated

**Should be marked Fixed (16 bugs):**

| Bug # | Reason |
|-------|--------|
| #2 | `addRunRecord()` is synchronized |
| #6 | `schemaVersion` implemented |
| #18 | JDK 17+21 matrix in CI |
| #19 | Gradle plugin tests in CI |
| #49 | Git timeout added (shallow-clone fallback may need separate tracker) |
| #50 | Git process timeout implemented |
| #51 | Unmodifiable sets in DependencyMap |
| #62 | ThreadLocal in UsageStore |
| #63 | Null check in listFiles() |
| #64 | nanoTime() in TelemetryListener |
| #65 | endsWith-based isTestClass |
| #69 | Atomic writes via PersistenceSupport |
| #71 | Cross-platform path normalization |
| #76 | Tests now exist for all listed classes |
| #78 | timeout-minutes on all CI jobs |
| #79 | IT verification scripts in CI |

### 8.2 — TEST_PLAN.md vs. reality

| TEST_PLAN Section | Claims | Reality |
|-------------------|--------|---------|
| Tier 1–3 unit tests | "33/33 done ✅" | ✅ Matches — all test files exist |
| Edge cases E1–E58 | "58/58 ✅" | ✅ Matches — referenced test methods exist |
| Fixture projects F1–F5 | Described in detail | ❌ `test-fixtures/` directory does not exist |
| Cross-cutting C1–C11 | Described in detail | ❌ Not implemented as runnable tests |
| CI integration yaml | Described in TEST_PLAN | ⚠️ Partially matches actual ci.yml (structure differs) |
| "Total verifiable items: 125" | 33 + 23 + 11 + 58 | ❌ Only 91 verified (unit tests + edge cases); 34 scenarios exist only on paper |

### 8.3 — IMPROVEMENT_PLAN.md vs. reality

| Phase | Tasks | Implemented |
|-------|-------|-------------|
| Phase 1 — Safety & Correctness | 20 tasks | **≥8 completed** (bugs #2, #6, #50, #51, #62, #63, #64, #65, #69, #71) but IMPROVEMENT_PLAN.md not updated |
| Phase 2 — Error Handling | Task count varies | Unknown |
| Phase 3–5 | Remaining tasks | Not started |

---

## 9. Priority Recommendations

### Immediate — High Impact, Low Effort

1. **Update BUGS.md**: Mark the 16 bugs in §8.1 as Fixed.
2. **Write regression test for Bug B0**: Formalize BUG_TEST_RESULTS.md findings as a reproducible unit test that fails until member-level tracking works.
3. **Write regression test for Bug #5**: Overfit detection inconsistency — provide runs where aggregate vs. per-run APFD diverge.

### Short-Term — High Impact, Medium Effort

4. **Build F1 fixture (petclinic)**: `demo-petclinic.sh` and `spring-petclinic/` already exist in workspace; formalize as automated test.
5. **Build F4 fixture (multi-module-spring)**: Validates ReactorContext, the known weakest integration point.
6. **Implement C1 (state corruption recovery)** and **C8 (corrupt index)** as integration tests — both exercise critical robustness paths.

### Medium-Term — Medium Impact, Higher Effort

7. **Build F2 fixture (langchain4j-core)**: Scale testing with 164 test classes.
8. **Implement remaining C2–C11 scenarios**: Many have partial unit coverage; need end-to-end wiring.
9. **Add JaCoCo to CI** (#37): Coverage visibility across all modules.
10. **Structural-diff Tier 1 tests**: Impact-weighted scoring doesn't require agent changes.

### Deferred

11. **Build F3/F5 (Gradle fixtures)**: Contingent on Gradle plugin maturity.
12. **Structural-diff Tiers 2–3**: Blocked on member-level agent instrumentation (root cause of B0).
13. **P2/P3 bug fixes**: Documentation, hygiene, performance items.

---

## 10. Test Count Reconciliation

| Source | Claimed | Verified | Delta |
|--------|---------|----------|-------|
| TEST_PLAN: unit test gaps (Tier 1–3) | 33 classes "done" | 33 classes with tests ✅ | 0 |
| TEST_PLAN: edge cases (E1–E58) | 58 "all covered" | All referenced methods exist ✅ | 0 |
| TEST_PLAN: fixture scenarios | 27 scenarios | 0 implemented ❌ | -27 |
| TEST_PLAN: cross-cutting scenarios | 11 scenarios | 0 implemented (4 partial) ❌ | -11 |
| TEST_PLAN: "Total verifiable items" | 125 | 91 verified + 34 paper-only | -34 |
| BUGS.md: total issues | 88 + B0 | ≥16 fixed, ≥4 need verification, rest open | BUGS.md stale |
| BUGS.md: CI issues (#18, #19, #78, #79) | 4 "Open" | All 4 fixed in ci.yml | BUGS.md stale |
| IMPROVEMENT_PLAN: Phase 1 tasks | 20 tasks | ≥8 completed in code | Plan not updated |

---

*This document should be updated as bugs are fixed, fixtures are built, and BUGS.md is brought current.*
