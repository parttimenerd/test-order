# test-order — Known Issues & Bug Tracker

All issues found in this project, consolidated from `IMPROVEMENT_PLAN.md` and manual testing.
`Status`: **Open** (not fixed), **Fixed** (committed), **Won't Fix** (deliberate).

---

## Summary Table

| # | Priority | Area | Short description | Status |
|---|----------|------|-------------------|--------|
| B0 | **Confirmed Bug** | Scoring | Member-level scoring does not differentiate tests covering different methods of the same class | Fixed |
| 1 | P0 | Architecture | `TestOrderState` is a 1,360-line god class (weights, serialisation, EMA, APFD, GA optimisation, static coordination) | Open |
| 2 | P0 | Threading | `addRunRecord()` mutates `ArrayList` without synchronisation — `ConcurrentModificationException` in CI | Fixed |
| 3 | P0 | Threading | Race between `recordBreakdown()` and `setStatePath()` — missed run records | Fixed |
| 4 | P0 | Threading | `getPendingStatePath()` releases lock before caller reads breakdowns — run record built with empty breakdowns | Fixed |
| 5 | P0 | Correctness | Overfit detection uses different train/val split than expanding-window fold — accept/reject inconsistent | Fixed |
| 6 | P0 | Serialisation | No schema version in state file — no migration path on format change | Fixed |
| 7 | P0 | Maven Plugin | `DumpMojo`/`ShowOrderMojo` write to `System.out` instead of `getLog().info()` — output invisible in CI | Fixed |
| 8 | P0 | Release | No Maven artifact rollback — stale release on Central if `git push` fails after `mvn deploy` | Fixed |
| 62 | P0 | Threading | `UsageStore.active` shared across all threads — method-level parallelism silently corrupts dependency index | Fixed |
| 63 | P0 | Agent | `listFiles()` passes `File.listFiles()` null result into for-each — NPE crashes JVM on unreadable dirs | Fixed |
| 64 | P0 | Correctness | `TelemetryListener` uses `System.currentTimeMillis()` — NTP corrections produce negative durations, corrupt EMA | Fixed |
| 9 | P1 | Error Handling | 20+ `catch (Exception e)` blocks mask specific failure modes | Fixed |
| 10 | P1 | Error Handling | `e.printStackTrace(System.err)` in `UsageStore` — noisy, unstructured, unactionable | Fixed |
| 11 | P1 | Robustness | `toInt()`/`toDouble()` throw `NumberFormatException` on malformed state file — crash instead of recovery | Fixed |
| 12 | P1 | Robustness | `asMap()` cast in `loadJson()` throws `ClassCastException` if JSON has array where map expected | Fixed |
| 13 | P1 | Algorithm | `l2Penalty()` silently ignores mismatched `w.length != defaults.length` | Fixed |
| 14 | P1 | Algorithm | No validation that `WeightDef.min() <= WeightDef.max()` — jenetics throws on invalid range | Fixed |
| 15 | P1 | Gradle | `TestOrderPlugin.java` monolith with broad `catch (Exception e)` handling | Fixed |
| 16 | P1 | Gradle | Race conditions in parallel Gradle builds — shared state/hash files written without file locking | Fixed |
| 17 | P1 | Gradle | Missing 5 of 10 Maven goals (`select`, `run-remaining`, `optimize`, `show-order`, `dump`) | Fixed |
| 18 | P1 | CI | JDK matrix covers only JDK 17 — no validation on JDK 21 or latest LTS | Fixed |
| 19 | P1 | CI | Gradle plugin ITs not in CI pipeline — Gradle regressions undetected | Fixed |
| 20 | P1 | Static State | `PriorityMethodOrderer` static fields never cleaned up between runs in same JVM — stale scores in Gradle daemon | Fixed |
| 49 | P1 | Change Detection | `HEAD~1` does not exist in fresh/shallow clones — returns empty set, all tests score 0 | Fixed |
| 50 | P1 | Change Detection | `GitChangeDetector.runGit()` has no process timeout — hung `git diff` blocks build indefinitely | Fixed |
| 51 | P1 | Dependency Map | Row-deduplicated sets share mutable `HashSet` references — consumer mutation corrupts all tests sharing that set | Fixed |
| 52 | P1 | Telemetry | `putIfAbsent` for class start times — second run of same class (parameterized/`@RepeatedTest`) records wrong duration | Fixed |
| 65 | P1 | Agent | `isTestClass()` matches `contains("Test")` — `ContestService`, `ProtestHandler` silently skipped from instrumentation | Fixed |
| 66 | P1 | Threading | `callEndTestClass()` ends wrong class under `@Execution(CONCURRENT)` — silent dependency index corruption | Fixed |
| 67 | P1 | Shading | `test-order-core` shade plugin has zero relocations — classpath conflicts with lz4, RoaringBitmap, tinylog, night-config | Fixed |
| 68 | P1 | Maven Plugin | `System.setProperty("argLine")` is JVM-global — leaks wrong agent paths into sibling modules | Fixed |
| 69 | P1 | Correctness | `TestOrderState.save()` writes directly to target — JVM crash leaves half-written file, all history lost | Fixed |
| 70 | P1 | Agent | `ClassIdMap.computeIfAbsent` returns `null` on ID exhaustion — NPE on future JDKs | Fixed |
| 71 | P1 | Portability | `FileHashStore` uses `Path.toString()` as key — `\` on Windows vs `/` on Unix breaks cross-platform detection | Fixed |
| 21 | P2 | Duplication | Set-cover algorithm nearly identical in `TestScorer` and `MethodScorer` — fixes must be applied twice | Fixed |
| 22 | P2 | Duplication | 15–20% boilerplate duplicated between Maven and Gradle plugins | Open |
| 23 | P2 | Performance | Set-cover greedy in `TestScorer` is O(t²) — slow for codebases with 10,000+ tests | Fixed |
| 24 | P2 | Memory | `SourceFileModel` allocates `int[fileLength]` for brace/paren depth — 400 MB for 100 large files in parallel | Fixed |
| 25 | P2 | API Design | `TestScorer` has 4+ constructors with no builder and no Javadoc — library users can't figure out which to use | Fixed |
| 26 | P2 | API Design | Configuration property names scattered across files — no central constants class | Fixed |
| 27 | P2 | API Design | Property naming inconsistent: `testorder.index.path` vs `testorder.stateFile` vs `testorder.score.newTest` | Fixed |
| 28 | P2 | Documentation | ~40% of public methods lack Javadoc | Open |
| 29 | P2 | Documentation | README has no troubleshooting section | Fixed |
| 30 | P2 | Documentation | No FAQ for JaCoCo, parameterised tests, Spring slices, Kotest | Fixed |
| 31 | P2 | Documentation | No CONTRIBUTING.md; SECURITY.md (bytecode instrumentation model) missing | Fixed |
| 32 | P2 | Hygiene | Root directory cluttered with 15+ working documents and `research.html` (109 KB) | Open |
| 33 | P2 | Hygiene | 5 example modules all build in CI — slow rebuild on every push | Fixed |
| 34 | P2 | Hygiene | 4 deprecated methods/records in `TestOrderState` — no callers, no `@forRemoval`, no removal timeline | Fixed |
| 35 | P2 | Build | No Checkstyle, SpotBugs, or ErrorProne in root build | Open |
| 36 | P2 | Build | No Maven Enforcer plugin — dependency convergence issues undetected | Fixed |
| 37 | P2 | Build | No project-wide JaCoCo reporting in root build | Fixed |
| 38 | P2 | Parser | `SourceFileModel` blind to annotation-processor-generated code (Lombok `@Data`, MapStruct) | Open |
| 39 | P2 | Observability | When genetic optimiser silently skips (< 3 failure runs), no log message tells the user | Fixed |
| 40 | P2 | Observability | `compactToOutcome()` silently drops corrupted run records (filters nulls, no warning) | Fixed |
| 41 | P2 | Security | No file-size check before LZ4 decompression — crafted 1 GB compressed payload causes DoS | Fixed |
| 42 | P2 | Release | `release.py` leaves local git tags on push failure — retry conflicts on tag names | Fixed |
| 43 | P2 | Release | No dedicated GitHub Actions release workflow — depends on single person's machine | Fixed |
| 53 | P2 | Testing | No example or IT for `@ParameterizedTest` with test-order — unknown if ordering or duration aggregation works | Fixed |
| 54 | P2 | Testing | No example or IT for JaCoCo + test-order agent coexistence | Fixed |
| 55 | P2 | Testing | No example or IT for Spring Boot test slices (`@DataJpaTest`, `@WebMvcTest`, `@SpringBootTest`) | Fixed |
| 56 | P2 | Testing | No example or IT for Kotlin Kotest framework | Open |
| 57 | P2 | Testing | No benchmarks for scoring, set-cover, or `DependencyMap` serialisation | Fixed |
| 44 | P3 | Performance | `ClassTransformer` caches grow unbounded — memory leak in very large builds | Fixed |
| 45 | P3 | Performance | `BOUNDED_GENERICS` regex limited to 4 nesting levels — silently falls back for deeply nested generics | Fixed |
| 46 | P3 | Robustness | `setFailurePruneThreshold()` accepts negative values, NaN, Infinity — garbage-in garbage-out | Fixed |
| 47 | P3 | Robustness | `TelemetryListener` saves state only at `testPlanExecutionFinished()` — JVM crash loses all data | Fixed |
| 48 | P3 | Data | `RunRecord` capped at `MAX_HISTORY_RUNS = 50`, hardcoded and not configurable; no history thinning | Fixed |
| 58 | P3 | Change Detection | Non-git projects (`IOException` from `ProcessBuilder("git"...)`) crash instead of falling back to hash detection | Fixed |
| 59 | P3 | Scoring | Newly added classes not yet in dependency map are silently ignored — tests for new code not prioritised on first order run | Fixed |
| 60 | P3 | Observability | No debug mode showing which change mode was used, which classes changed, or per-test score breakdown | Fixed |
| 61 | P3 | Config | Weights config precedence undocumented — POM settings silently overridden by system properties | Fixed |
| 72 | P3 | Performance | `StructuralDiff.diffGitChanges` spawns one `git show` per changed file — 100 changed files = 103+ processes | Fixed |
| 73 | P3 | Performance | `testOrderShowOrder` calls `scorer.score()` per comparison during sort — O(n log n) recomputations, no caching | Fixed |
| 74 | P3 | Correctness | `CombinedMojo` optimize trigger uses `runs().size() % N` — after MAX_HISTORY_RUNS cap, modulo fires unpredictably or never | Fixed |
| 75 | P3 | Correctness | `extractXmlTag` matches first `<groupId>` in POM — may pick `<parent>` or `<dependency>` groupId, not project's own | Fixed |
| 76 | P3 | Testing | No unit tests for `PriorityMethodOrderer`, `UsageStore`, `MethodScorer`, or any Mojo except `AbstractTestOrderMojo`/`SurefireHelper` | Fixed |
| 77 | P3 | Testing | `PriorityClassOrdererTest` tests using `System.setProperty` are not thread-safe — no `@Timeout`, no parallel guards | Fixed |
| 78 | P3 | CI | No `timeout-minutes` on any CI job — hung builds consume 6-hour GitHub Actions default | Fixed |
| 79 | P3 | CI | `-Dtestorder.it=true` not verified — ITs may be silently skipped with no detection | Fixed |
| 80 | P3 | IT | No invoker IT for multi-module projects — `ReactorContext` untested end-to-end | Fixed |
| 81 | P3 | IT | No invoker IT for `select` or `run-remaining` modes | Fixed |
| 82 | P3 | IT | IT fixtures hardcode JUnit 5.10.2 while parent POM uses JUnit 6.x | Fixed |
| 83 | P3 | Build | `test-order-agent/pom.xml` uses `<executable>cp</executable>` — breaks on Windows; offline builds fail | Fixed |
| 84 | P3 | Scoring | `computeMedianDuration` retains stale duration entries for renamed/deleted test classes | Fixed |
| 85 | P3 | Scoring | `toGitPrefix` returns lone `/` when `sourceRoot == projectRoot` — `git diff` path filter matches nothing | Fixed |
| 86 | P3 | Robustness | `ClassNameTrie.readVarInt` has no shift bounds check — 5+ continuation bytes in corrupt data cause silent integer overflow | Fixed |
| 87 | P3 | Robustness | `DependencyMap.loadBinary` V4 member counts not bounds-checked — `memberCount = MAX_INT` causes OOM | Fixed |
| 88 | P3 | Robustness | `IntelligentClassFilter.cacheSize` never decremented on `clearCache()` — caching permanently disabled after first cache clear | Fixed |
| 89 | P3 | Scripts | `test_harness.py` hardcodes absolute path `/Users/i560383_1/...`; uses `shell=True` → command injection risk | Won't Fix |
| 90 | P3 | Scripts | `release.py` `except` block doesn't undo `git commit`/`git tag` already created — requires manual git surgery | Fixed |
| 91 | P3 | Hygiene | Example modules contain 15+ checked-in build artifacts (`.out`, `.test-order-state`, `.lz4`) not in `.gitignore` | Fixed |
| 92 | P3 | Examples | Example POMs add plugin with no `<configuration>` block — running `mvn test` demonstrates nothing | Fixed |
| 93 | P3 | Build | `test-order-benchmarks` builds in CI on every run but has no tests — pure overhead | Won't Fix |
| 95 | P3 | Memory | `PriorityMethodOrderer` static fields never cleared — entire `DependencyMap` and `TestOrderState` persist across Gradle daemon builds | Fixed |
| 96 | P0 | Correctness | `TelemetryListener` records `@Nested` class failures under nested name (`Outer$Inner`) but `PriorityClassOrderer` looks up top-level (`Outer`) — nested test failures silently never prioritised | Fixed |
| 97 | P1 | Correctness | `StructuralDiff.diffMethods` uses `HashSet` for overload body hashes — duplicate hashes (e.g. multiple abstract overloads) collapse, silently missing added/removed overloads | Fixed |
| 98 | P1 | Correctness | `LineDiff.diffText()` returns changes in reverse file order — LCS traceback collects bottom-to-top but never reverses before join | Fixed |
| 99 | P1 | Correctness | `SourceFileModel.removeCommentsAndEmptyLines()` missing escape handling in text blocks — `\"""` prematurely closes text block, corrupting `compactBody` | Fixed |
| 100 | P2 | Threading | `PriorityMethodOrderer` static fields not `volatile` — writes in `synchronized setPendingState()` not guaranteed visible to unsynchronized `orderMethods()` reads | Fixed |
| 101 | P2 | Maven Plugin | `ChangeDetectionHelper` passes sub-module `basedir` as projectRoot for git operations — multi-module builds get empty change sets, defeating prioritisation | Fixed |
| 102 | P2 | Maven Plugin | `resolveArtifact` reads `settings.localRepository` from project properties (always null) instead of Maven session — custom local repo configs break agent lookup | Fixed |
| 103 | P2 | Maven Plugin | `ShowOrderMojo` structural diff mode doesn't match change detection mode for `auto`/`since-last-run` — complexity scores wrong | Fixed |
| 104 | P2 | Correctness | `ScoringOptimizer` overfit check uses simple-average `evaluateWeights` while GA uses recency-weighted `evaluateExpandingWindow` — false positive/negative overfit detection | Fixed |
| 105 | P2 | Threading | `TelemetryListener` state file read-modify-write not atomic across forked JVMs — `forkCount > 1` silently loses telemetry data | Fixed |
| 106 | P3 | Correctness | `StructuralDiff.diffSinceLastCommit` doesn't check `HEAD~1` existence — initial/shallow repos get spurious ADDED changes instead of MODIFIED | Fixed |
| 107 | P3 | Robustness | `GitChangeDetector.readFileFromGit()` called with null `commitRef` for untracked files — executes `git show null:<path>` | Fixed |

---

## Bug B0 — Confirmed: Member-Level Scoring Does Not Differentiate Tests

**Source:** `BUG_TEST_RESULTS.md` — manual testing with `test-order-fields-methods-example`
**Priority:** Confirmed Bug (HIGH severity)
**Status:** Fixed

### Description
When multiple test classes depend on the same source class but exercise different methods, the plugin assigns identical scores to all of them regardless of which specific methods were modified. Tests that only call `addItem()` receive the same score as tests that only call `setMetadata()`, even when only `addItem()` was changed.

### Root Cause
Member-level scoring logic existed in `TestScorer`, but the learn-mode file pipeline did not persist and reload member data end-to-end:
1. `UsageStore` wrote only `.deps`/`.mdeps` in `outputDir` mode.
2. `DependencyMap.aggregate(...)` and `aggregateFromDepsDirectory(...)` only loaded `.deps`/`.mdeps`.

As a result, `hasMemberDeps()` remained effectively false in normal runs and scoring fell back to class-level dependencies.

### Fix Implemented
1. `UsageStore.flush()` now writes class-member and method-member files (`.members`, `.mmembers`) in `outputDir` mode.
2. `DependencyMap.aggregate(...)` now loads `.members` and `.mmembers`.
3. `DependencyMap.aggregateFromDepsDirectory(...)` now loads `.members` and `.mmembers` in the parallel aggregation path.
4. Added regressions in `DependencyMapTest` to verify both aggregation paths load member-level files.

### Confirmed Failure Scenarios
- Modify `addItem()` in `WideCoverageService` → `ItemMethodsTest` and `MetadataMethodsTest` both receive score 3 (identical). Expected: `ItemMethodsTest` score > `MetadataMethodsTest`.
- Modify `setMetadata()` → same result in reverse direction.
- Modify both item and metadata methods → `CombinedMethodsTest` gets +2 bonus from test count only, not from method-level overlap.

### Fix Required
1. Enable and verify member-level dependency collection during learn mode (agent instrumentation must record method-granularity edges, not just class-level).
2. Verify `DependencyMap.hasMemberDeps()` returns `true` after a learn-mode run.
3. Ensure `ChangedMembers` analysis passes member keys that match stored member dependency keys.
4. Add unit tests: `ChangeComplexityTest`, `DepsAndScoringTest` method-level variants.

---

## P0 — Critical Bugs

### #1 `TestOrderState` is a 1,360-line god class
**File:** `test-order-core/src/main/java/me/bechberger/testorder/TestOrderState.java`

Handles weights, JSON serialisation, EMA smoothing, APFD computation, genetic algorithm optimisation, AND static thread coordination in a single class. Impossible to test individual concerns in isolation; any change risks unintended interaction with other concerns.

**Fix:** Split into `StateSerializer`, `ScoringOptimizer`, `PendingRunCoordinator`, `TestOrderState` (domain model only), `APFDCalculator`.

---

### #2 `addRunRecord()` not synchronised
**File:** `TestOrderState.java`

Mutates a plain `ArrayList<RunRecord>` without synchronisation. Concurrent callers (e.g., parallel Surefire forks writing their results) can corrupt the list or throw `ConcurrentModificationException`.

**Fix:** Replace with `CopyOnWriteArrayList` or synchronise on the instance.

---

### #3 Race between `recordBreakdown()` and `setStatePath()`
**File:** `TestOrderState.java`

`recordBreakdown()` uses a lock-free `ConcurrentHashMap.put` while `setStatePath()` is `synchronized`. `hasPendingData()` can return `false` even when both values are set, causing run records to be silently missed.

**Fix:** Replace static `pendingBreakdowns`/`pendingStatePath` with a single `AtomicReference<PendingRunData>` holding an immutable snapshot.

---

### #4 `getPendingStatePath()` releases lock too early
**File:** `TestOrderState.java`

Releases the monitor before the caller can read `getPendingBreakdowns()`. A concurrent `resetPending()` can clear breakdowns between the two calls, building a run record with an empty breakdown.

**Fix:** Provide a single atomic `getAndResetPending()` method that returns an immutable snapshot under the lock.

---

### #5 Overfit detection inconsistency in `optimize()`
**File:** `TestOrderState.java`

The final overfit check uses aggregate APFD whereas the expanding-window fold fitness uses per-run recency-weighted APFD. The two metrics can diverge, causing the genetic algorithm to reject good weights or accept overfitted ones.

**Fix:** Align the final check to use the same expanding-window evaluation as the fitness function.

---

### #6 No schema version in state file
**File:** `TestOrderState.java`

No `"schemaVersion"` field in the JSON output. Future format changes have no migration path; older readers silently produce wrong state when loading a newer file.

**Fix:** Add `"schemaVersion": 1`. Reject `schemaVersion > CURRENT` with a clear error; discard and start fresh when older.

---

### #7 `DumpMojo`/`ShowOrderMojo` use `System.out`
**Files:** `DumpMojo.java`, `ShowOrderMojo.java`

`System.out.println()` is invisible in CI/CD logs and IDE Maven runners that capture `getLog()` output only.

**Fix:** Replace all `System.out.print*()` calls with `getLog().info()`.

---

### #8 No Maven artifact rollback
**File:** `release.py`

If `git push` or GitHub release creation fails after `mvn deploy`, artifacts are already live on Maven Central but source is rolled back locally. There is no mechanism to retract the deployed artifacts.

**Fix:** `release.py` now pushes git commits/tags before running Maven deploy, so a push failure aborts the release before any remote artifacts are published. Regression tests verify the sequencing and ensure deploy is skipped when push fails.

---

### #62 `UsageStore.active` races under method-level parallelism
**File:** `test-order-agent/src/main/java/me/bechberger/testorder/agent/UsageStore.java`

`active` is a single `volatile` reference shared across all threads. Under Surefire method-level parallelism, concurrent test classes overwrite each other's active tracker, recording dependencies into the wrong test class. The dependency index is silently corrupted.

**Fix:** Replace with `ThreadLocal<ActiveTrackers>` scoped to the test-class invocation thread.

---

### #63 `listFiles()` NPE in agent `premain`
**File:** `ProjectStructureAnalyzer.java`

`File.listFiles()` returns `null` on permission errors or dangling symlinks. The result is passed directly into a for-each loop, causing NPE that crashes JVM startup.

**Fix:** Guard with null check; log warning and skip unreadable directories.

---

### #64 `TelemetryListener` uses `currentTimeMillis()` for durations
**File:** `TelemetryListener.java`

NTP corrections, DST changes, or clock adjustments during a test run produce negative elapsed times that permanently corrupt EMA duration estimates.

**Fix:** Replace with `System.nanoTime()` for elapsed-time measurement; convert to milliseconds only when persisting.

---

## P1 — High Priority Issues

### #9 Broad `catch (Exception e)` blocks
20+ instances across all modules mask specific failure modes (`IOException` vs `SecurityException` vs `NumberFormatException`). Wrong recovery paths chosen silently.

**Fix:** Narrow each catch to the specific exception(s) thrown by the enclosed code.

---

### #10 `e.printStackTrace()` in `UsageStore`
**File:** `UsageStore.java` lines 334, 354

Unstructured output interleaved with test output; unactionable for users.

**Fix:** Replace with `AgentLogger.error()`.

---

### #11 State file load crashes on malformed numbers
**File:** `TestOrderState.java`

`toInt()`/`toDouble()`/`toLong()` throw uncaught `NumberFormatException` on any non-numeric weight string. A hand-edited or partially corrupted state file crashes the build.

**Fix:** Wrap in try-catch, return defaults, log warning.

---

### #12 State file load crashes on wrong JSON types
**File:** `TestOrderState.java`

`Util.asMap()` cast throws `ClassCastException` if JSON has an array where a map is expected. Corrupted state file crashes instead of recovering.

**Fix:** Use `instanceof` check before cast; fall back to defaults with a warning.

---

### #13 `l2Penalty()` ignores mismatched weights length
**File:** `TestOrderState.java`

Silently ignores `w.length != defaults.length`. Weight structure drift goes undetected.

**Fix:** `assert w.length == defaults.length` or throw `IllegalArgumentException`.

---

### #14 No `WeightDef` range validation
**File:** `TestOrderState.java`

No check that `WeightDef.min() <= WeightDef.max()`. jenetics throws an unhandled exception when the optimizer is invoked.

**Fix:** `checkArgument(min <= max)` in the `WeightDef` constructor.

---

### #15 Gradle plugin is a 754-line monolith
**File:** `TestOrderPlugin.java`

Historically, the Gradle plugin concentrated most responsibilities in one class and swallowed configuration errors via broad `catch (Exception e)` blocks, making failures hard to diagnose.

**Fix:** Fixed by splitting core responsibilities into `TestOrderExtensionConfigurator`, `LearnModeConfigurator`, `OrderModeConfigurator`, and `UtilityTaskRegistrar`, and by narrowing the remaining change-detection helper catches in `TestOrderPlugin.java` from `Exception` to `IOException` so invalid `changeMode` values now fail fast instead of being silently ignored. Validated with focused regressions in `TestOrderPluginTest` under Java 17.

---

### #16 Race conditions in parallel Gradle builds
**File:** `TestOrderPlugin.java`

Concurrent test tasks write to shared `.test-order-state` and hash files without file locking. State is silently corrupted in multi-module Gradle projects.

**Fix:** Fixed by locking Gradle hash snapshot writes in `snapshotSingleDir(...)` and strengthening `PersistenceSupport.withFileLock(...)` to serialize same-JVM callers per lock path before taking the OS-level file lock. Validated with focused regressions in `PersistenceSupportTest` and `TestOrderPluginTest` under Java 17.

---

### #17 Gradle missing 5 of 10 Maven goals
Missing: `select`, `run-remaining`, `optimize`, `show-order`, `dump`.

Users discover missing features at runtime; no documentation of the gap.

**Fix:** Fixed by registering and wiring `testOrderSelect`, `testOrderRunRemaining`, `testOrderOptimize`, `testOrderShowOrder`, and `testOrderDump` in the Gradle plugin. Covered by task-registration tests in `TestOrderPluginTaskRegistrationTest` and focused integration coverage in `TestOrderPluginIntegrationTest`.

---

### #18 JDK matrix covers only JDK 17
**File:** `.github/workflows/ci.yml`

Targets JDK 17+, but only tests on 17. Code that fails on JDK 21 or latest LTS ships undetected.

---

### #19 Gradle ITs not in CI pipeline
Gradle plugin regressions go undetected between releases.

---

### #20 `PriorityMethodOrderer` static state not cleaned up
**File:** `PriorityMethodOrderer.java`

Static fields (`pendingState`, `methodWeights`, `enabled`, `depMap`, `changedClasses`) are never cleared between JVM reuse cycles (Gradle daemon, IDE). Stale scores persist into subsequent builds.

**Fix:** Reset in `testPlanExecutionFinished()` or use `ExtensionContext.Store`.

---

### #49 `HEAD~1` fails in shallow/single-commit repos
**File:** `GitChangeDetector.java`

Returns empty set when `HEAD~1` does not exist. All tests score 0 on first CI run or in shallow clones.

**Fix:** Detect non-zero exit code and fall back to treating all tracked source files as changed.

---

### #50 No git process timeout
**File:** `GitChangeDetector.java`

A hung `git diff` on NFS/slow storage blocks the build indefinitely.

**Fix:** `process.waitFor(30, TimeUnit.SECONDS)`; destroy + return empty set with warning if timed out.

---

### #51 Row-deduplicated sets share mutable `HashSet` references
**File:** `DependencyMap.java`

If any consumer mutates a returned dependency set, it corrupts all tests sharing that same deduplicated row.

**Fix:** Wrap in `Collections.unmodifiableSet()` before assignment.

---

### #52 `putIfAbsent` for class start times drops repeated-class durations
**File:** `TelemetryListener.java`

If the same test class executes twice (parameterised config, `@RepeatedTest`), only the first start time is recorded. The second execution's duration is wrong.

**Fix:** Use a `List<Long>` of start times per class; sum or average on completion.

---

### #65 `isTestClass()` false positives via `contains("Test")`
**File:** `IntelligentClassFilter.java`

Production classes like `ContestService`, `ProtestHandler`, `MockitoExtension` match `contains("Test")` / `contains("Mock")` and are silently excluded from instrumentation. Dependency edges to these classes are never recorded.

**Fix:** Replace with `endsWith("Test")`, `endsWith("Tests")`, `endsWith("TestCase")`, `startsWith("Test")`.

---

### #66 `callEndTestClass()` ends wrong class under `@Execution(CONCURRENT)`
**File:** `TelemetryListener.java`

`callEndTestClass()` ends "whatever is current" rather than the specific class whose test finished. Under JUnit's own `@Execution(CONCURRENT)`, thread A ending ClassX actually ends ClassY's recording.

`SurefireHelper.validateNoClassLevelParallel` only checks Surefire config, not JUnit's annotation.

**Fix:** Pass class name explicitly to `callEndTestClass()`; detect JUnit `@Execution(CONCURRENT)` annotation.

---

### #67 No shade relocations — classpath conflicts
**File:** `test-order-core/pom.xml`

lz4, RoaringBitmap, tinylog, night-config, jenetics bundled without relocation. Projects using different versions get silent `NoSuchMethodError` or behaviour changes at runtime.

**Fix:** Add `<relocations>` for all shaded libraries.

---

### #68 `System.setProperty("argLine")` JVM-global in multi-module builds
**File:** `AbstractTestOrderMojo.java`

Module A's agent attachment string leaks into module B with wrong absolute paths.

**Fix:** Use only `project.getProperties().setProperty()`.

---

### #69 Non-atomic state file writes
**File:** `TestOrderState.java` (and `FileHashStore.java`, `MethodHashStore.java`, `DependencyMap.java`)

Direct write to target file — JVM crash during write leaves half-written file. All test history permanently lost on next load.

**Fix:** Write to temp file, then `Files.move(tmp, target, ATOMIC_MOVE)`.

---

### #70 `ClassIdMap.computeIfAbsent` returns null on exhaustion
**File:** `ClassIdMap.java`

`ConcurrentHashMap.computeIfAbsent` contract prohibits null return values. Behaviour is implementation-defined and may NPE on future JDKs when ID space fills.

**Fix:** Throw a descriptive `IllegalStateException` when ID space is exhausted.

---

### #71 `FileHashStore` uses `Path.toString()` as map key
**File:** `FileHashStore.java`

`Path.toString()` produces `\` on Windows, `/` on Unix. After switching OS, every file appears changed.

**Fix:** Normalise path keys to use `/` before persisting.

---

## P2 — Medium Priority Issues

### #21 Set-cover algorithm duplicated in `TestScorer` and `MethodScorer`
Bug fixes must be applied twice; divergence likely.

**Fix:** Fixed via shared `SetCoverComputer<T, C>` in `test-order-core`, now used by both `TestScorer` and `MethodScorer` for greedy set-cover ordering.

---

### #22 Maven/Gradle plugin boilerplate duplicated (15–20%)
Mode resolution, change detection, hash snapshotting duplicated.

**Fix:** Extract shared logic into `test-order-core` helpers; plugins become thin adapters.

---

### #23 Set-cover greedy is O(t²)
**File:** `TestScorer.java`

Linear scan per iteration. Slow for 10,000+ test suites.

**Fix:** Fixed by moving greedy set-cover to `SetCoverComputer`, which uses a priority queue with lazy count updates (`QueueEntry`) and adjacency-based decrement propagation.

---

### #24 `SourceFileModel` OOM for large parallel builds
Per-file `int[fileLength]` arrays for brace/paren depth. 100 files × 500 KB = 400 MB heap.

---

### #25 `TestScorer` has 4+ overloaded constructors with no builder
Library users cannot determine which constructor to use. No `@param` Javadoc.

**Fix:** Fixed by introducing and documenting `TestScorer.Builder` as the primary construction API, adding a `TestScorer.builder(...)` factory entrypoint, and adding constructor/builder Javadocs that describe required and optional inputs.

---

### #26/#27 Configuration property names scattered and inconsistently named
No central constants class. Mix of `testorder.index.path` (kebab), `testorder.stateFile` (camelCase), `testorder.score.newTest` (camelCase after dot). Confusing for users.

**Fix:** Fixed by introducing `MavenPluginConfigKeys` as the Maven plugin’s centralized property-key registry and replacing scattered string literals across `AbstractTestOrderMojo`, `PrepareMojo`, `CombinedMojo`, `SelectMojo`, `ShowOrderMojo`, `DumpMojo`, `RunRemainingMojo`, and `ReactorContext`. Added canonical-key support for major shared options (e.g., `testorder.index.path`, `testorder.state.path`, `testorder.methodOrder.enabled`) while preserving legacy Maven user-property aliases for backward compatibility.

---

### #28 ~40% of public methods lack Javadoc

---

### #29 README has no troubleshooting section

---

### #30 No FAQ (JaCoCo, parameterised tests, Spring slices, Kotest)

---

### #31 No CONTRIBUTING.md; SECURITY.md missing bytecode model documentation

**Fix:** Fixed by adding root-level `CONTRIBUTING.md` with development and validation guidance and `SECURITY.md` with explicit notes about learn-mode bytecode instrumentation, file/persistence boundaries, decompression, git process execution, and multi-agent interactions.

---

### #32 Root directory cluttered with 15+ working documents and 109 KB `research.html`

---

### #33 5 example modules all rebuild in CI

---

### #34 4 deprecated records/methods in `TestOrderState` with no callers and no `@forRemoval`

---

### #35/#36 No Checkstyle, SpotBugs, ErrorProne, Maven Enforcer

`#36` is fixed (root Maven Enforcer plugin is configured). `#35` remains open for missing root-level Checkstyle/SpotBugs/ErrorProne integration. Root `.editorconfig` exists.

---

### #37 No JaCoCo

Fixed by wiring `jacoco-maven-plugin` in the root parent build with inherited `prepare-agent` and `check` executions, plus a root-only `report-aggregate` execution that writes project-wide coverage output under `target/site/jacoco-aggregate`.

---

### #38 `SourceFileModel` blind to Lombok/MapStruct generated code

---

### #39 Genetic optimiser silent skip
When `< 3` failure runs exist, optimiser exits with no message. Feature appears broken.

---

### #40 `compactToOutcome()` silently drops corrupted run records (null filter)

---

### #41 No decompression bomb guard in `DependencyMap.loadBinary()`
A crafted 1 GB compressed dependency index could cause DoS via decompression.

**Fix:** Check `Files.size(indexFile) < 1 GB` before decompression.

---

### #42/#43 Fragile release process
`release.py` leaves local tags on push failure. GitHub Actions release workflow is now present.

**Fix:** #43 fixed by repository-level release automation in `.github/workflows/release.yml` (tag and manual dispatch triggers, Maven deploy with signing, GitHub release publication).

---

### #53–56 No compatibility ITs
No IT for `@ParameterizedTest`, JaCoCo coexistence, Spring Boot slices, or Kotest.

**Fix:** #53 fixed by fixture-backed coverage in `ParameterizedTestOrderingIT` using `fixture-parameterized-tests`; #54 fixed by `JaCoCoCoexistenceIT` using `fixture-jacoco`; #55 fixed by `SpringBootSlicesIT` using `fixture-spring-boot-slices`. #56 (Kotest) remains open.

---

### #57 No scoring/serialisation benchmarks

**Fix:** Fixed via JMH benchmarks in `test-order-benchmarks`, including `CoreAlgorithmBenchmark` for scoring (`TestScorer`), set-cover (`SetCoverComputer`), and dependency index serialization/load (`DependencyMap.save/load`), plus baseline artifacts in `test-order-benchmarks/baselines`.

---

## P3 — Low Priority Issues

### #44 `ClassTransformer` caches grow unbounded (memory leak in large builds)

### #45 `BOUNDED_GENERICS` regex limited to 4 nesting levels

### #46 `setFailurePruneThreshold()` accepts negative/NaN/Infinity values

### #47 `TelemetryListener` saves state only at plan-finished — JVM crash loses all data

### #48 `MAX_HISTORY_RUNS = 50` hardcoded, not configurable; no history thinning

### #58 Non-git projects throw `IOException` instead of falling back gracefully

### #59 Newly added classes (not yet in dependency map) silently ignored in overlap scoring

### #60 No debug mode: users cannot see which change mode was used or why a test scored as it did

### #61 Weights config precedence undocumented — POM settings silently overridden by system properties

### #72 `StructuralDiff.diffGitChanges` spawns one `git show` per changed file (100 files = 103+ processes)

### #73 `testOrderShowOrder` recomputes `scorer.score()` per comparison — no caching, O(n log n) recomputations

### #74 `CombinedMojo` optimize trigger misfires after `MAX_HISTORY_RUNS` cap

### #75 `extractXmlTag` may match `<parent>` or `<dependency>` `<groupId>` instead of project's own

### #76 No unit tests for `PriorityMethodOrderer`, `UsageStore`, `MethodScorer`, or Maven Mojos (beyond `AbstractTestOrderMojo`/`SurefireHelper`)

### #77 `PriorityClassOrdererTest` `System.setProperty` tests not thread-safe; no `@Timeout` guards

### #78 No `timeout-minutes` on CI jobs — hung builds burn 6-hour GitHub Actions credit

### #79 `-Dtestorder.it=true` verification absent — ITs may be silently skipped

### #80 No invoker IT for multi-module projects — `ReactorContext` untested end-to-end

**Fix:** Fixed by invoker-style fixture coverage in `MavenPluginIT`, which verifies the `reactor-learn-mode` project uses the shared `.test-order` directory across modules and produces module-specific Surefire output.

### #81 No invoker IT for `select` or `run-remaining` modes

**Fix:** Fixed by invoker-style fixtures in `MavenPluginIT` for `select-mode`, `select-mode-junit6`, `run-remaining-mode`, and `run-remaining-mode-junit6`, asserting both selection files and the actual executed Surefire report set.

### #82 IT fixtures hardcode JUnit 5.10.2 while parent POM uses JUnit 6.x

### #83 `test-order-agent/pom.xml` uses `<executable>cp</executable>` — breaks on Windows and in offline mode

### #84 `computeMedianDuration` retains stale entries for renamed/deleted test classes

### #85 `toGitPrefix` returns lone `/` when `sourceRoot == projectRoot` — `git diff` filter matches nothing

### #86 `ClassNameTrie.readVarInt` shift has no bounds check — corrupt data causes silent integer overflow

### #87 `DependencyMap.loadBinary` V4 member counts not bounds-checked — `memberCount = MAX_INT` causes OOM

### #88 `IntelligentClassFilter.cacheSize` not decremented on `clearCache()` — caching permanently broken after first clear

### #89 `test_harness.py` hardcodes `/Users/i560383_1/...` path; `shell=True` creates command injection risk

### #90 `release.py` `except` block doesn't undo `git commit`/`git tag` — requires manual git surgery

### #91 Example modules contain 15+ checked-in build artifacts not in `.gitignore`

### #92 Example POMs add plugin with no `<configuration>` block — `mvn test` demonstrates nothing

### #93 `test-order-benchmarks` builds in CI on every run but has no tests — pure overhead

### #95 `PriorityMethodOrderer` static fields never cleared — entire `DependencyMap` and `TestOrderState` persist across Gradle daemon builds (memory leak)
