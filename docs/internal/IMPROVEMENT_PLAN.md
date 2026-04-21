# test-order Improvement Plan

57 tasks across 5 phases. Each task is self-contained with issue refs, target module/file, and concrete steps.

---

## Issue Inventory

### Critical (P0)

| # | Area | Issue | Impact |
|---|------|-------|--------|
| 1 | **Architecture** | `TestOrderState` is a 1,360-line god class handling weights, serialization, EMA, APFD, genetic optimization, AND static thread coordination | Untestable in isolation; impossible to modify one concern without risk to others |
| 2 | **Threading** | `addRunRecord()` mutates an `ArrayList` without synchronization; concurrent callers corrupt run history | Data loss / `ConcurrentModificationException` in CI |
| 3 | **Threading** | Race window between `recordBreakdown()` (lock-free `ConcurrentHashMap.put`) and `setStatePath()` (synchronized) — `hasPendingData()` can return false even when both values exist | Missed run records; telemetry silently lost |
| 4 | **Threading** | `getPendingStatePath()` releases lock before caller reads `getPendingBreakdowns()` — a concurrent `resetPending()` clears breakdowns between the two calls | Run record built with empty breakdowns |
| 5 | **Correctness** | Overfit detection in `optimize()` uses a different train/val split than the expanding-window fold fitness (aggregate vs per-run-recency-weighted), producing inconsistent accept/reject decisions | GA may reject good weights or accept overfit ones |
| 6 | **Serialization** | No schema version field in the state file JSON — future format changes have no migration path; older readers silently produce wrong state | Forward/backward compatibility broken on upgrade |
| 7 | **Maven Plugin** | `DumpMojo` and `ShowOrderMojo` use `System.out.println()` instead of `getLog().info()` | Output invisible in CI/CD logs and IDE Maven runners |
| 8 | **Release** | No Maven artifact rollback — if `git push` or GitHub release fails after `mvn deploy`, artifacts are live but source is rolled back | Phantom release on Maven Central with no matching tag |
| 62 | **Threading** | `UsageStore.active` is a single volatile reference shared by all threads — concurrent test classes in the same JVM (method-level parallelism) overwrite each other's tracker, silently recording dependencies into the wrong test class | Dependency index silently corrupt under Surefire method-level parallelism |
| 63 | **Agent** | `ProjectStructureAnalyzer.listFiles()` passes `dir.toFile().listFiles()` result directly into for-each — `File.listFiles()` returns `null` on permission errors/dangling symlinks, causing NPE in agent `premain` that crashes JVM startup | Build fails with cryptic NPE if source tree has unreadable directories |
| 64 | **Correctness** | `TelemetryListener` uses `System.currentTimeMillis()` for duration measurement — NTP corrections, DST changes, or clock adjustments during a test run produce negative durations that permanently corrupt EMA estimates | Duration data silently wrong; bad test ordering for affected tests |

### High (P1)

| # | Area | Issue | Impact |
|---|------|-------|--------|
| 9 | **Error Handling** | 20+ instances of `catch (Exception e)` across modules mask specific failure modes (`IOException` vs `SecurityException` vs `NumberFormatException`) | Hard to diagnose failures; wrong recovery path chosen |
| 10 | **Error Handling** | `e.printStackTrace(System.err)` in `UsageStore.java` (lines 334, 354) — unstructured, interleaved output | Noise in CI logs, unactionable for users |
| 11 | **Robustness** | `toInt()` / `toDouble()` / `toLong()` in `TestOrderState` throw uncaught `NumberFormatException` on malformed JSON strings — propagates as unhandled crash | State file with any non-numeric string in weight fields crashes the build |
| 12 | **Robustness** | `Util.asMap()` in `loadJson()` throws `ClassCastException` if JSON has an array where a map is expected (e.g., `"config": [1,2,3]`) — not caught | Corrupted/hand-edited state file crashes instead of recovering |
| 13 | **Algorithm** | `l2Penalty()` silently ignores mismatched `w.length != defaults.length` instead of requiring equal length | Weight structure drift goes undetected |
| 14 | **Algorithm** | No validation that `WeightDef.min() <= WeightDef.max()` before passing to `IntegerChromosome.of()` — jenetics throws unhandled exception on invalid range | Bad weight config crashes optimizer |
| 15 | **Gradle** | `TestOrderPlugin.java` (754 lines) is a monolith with 6 `catch (Exception e)` blocks — all responsibilities in one class; Maven plugin has 11 focused Mojos | Hard to test, debug, or extend Gradle support |
| 16 | **Gradle** | Race conditions in parallel Gradle builds — concurrent test tasks write to shared `.test-order-state` and hash files without file locking | Corrupted state in multi-module Gradle projects |
| 17 | **Gradle** | Missing 5 of 10 Maven goals (`select`, `run-remaining`, `optimize`, `show-order`, `dump`) — feature gap not documented | Users expect parity, discover missing features at runtime |
| 18 | **CI** | No JDK matrix — only tests on 17, but targets 17+; should validate on 21/latest LTS | Could ship code that fails on newer JDKs |
| 19 | **CI** | Gradle plugin integration tests not in CI pipeline — only Maven ITs run | Gradle regressions go undetected |
| 20 | **Static State** | `PriorityMethodOrderer` static fields (`pendingState`, `methodWeights`, `enabled`, etc.) never cleaned up between test runs in same JVM | Stale scores in daemon Gradle builds / IDE reuse |
| 49 | **Change Detection** | `changedSinceLastCommit()` uses `HEAD~1` which doesn't exist in fresh clones, single-commit repos, or shallow clones — returns empty set, all tests score 0 | No test prioritization on first commit or in CI shallow clones |
| 50 | **Change Detection** | `GitChangeDetector.runGit()` has no process timeout — a hung `git diff` on NFS/slow storage blocks the build indefinitely | Build hangs forever in degraded environments |
| 51 | **Dependency Map** | Row-deduplicated sets share mutable `HashSet` references across test classes — if any consumer mutates a returned set, it corrupts all tests sharing that dependency set | Silent data corruption if deps are modified post-load |
| 52 | **Telemetry** | `classStartTimes.putIfAbsent()` means if same test class executes twice concurrently (parameterized config, `@RepeatedTest`), only first start time is recorded — second run's duration is wrong | Wrong duration data for repeated/parameterized test classes |
| 65 | **Agent** | `IntelligentClassFilter.isTestClass()` matches `contains("Test")` / `contains("Mock")` / `contains("Stub")` on simple name — production classes like `ContestService`, `ProtestHandler`, `MockitoExtension`, `StubFactory` are silently skipped from instrumentation | Missing dependency edges for production classes with "Test"/"Mock" in name |
| 66 | **Threading** | `TelemetryListener.executionFinished` calls no-arg `callEndTestClass()` — with `@Execution(CONCURRENT)` on individual test classes, thread A ending ClassX actually ends ClassY's recording if ClassY started in between. `SurefireHelper.validateNoClassLevelParallel` only checks Surefire config, not JUnit's own `@Execution(CONCURRENT)` | Silent dependency data corruption with JUnit concurrent execution |
| 67 | **Shading** | `test-order-core` shade plugin has zero `<relocations>` — lz4, RoaringBitmap, tinylog, night-config, jenetics all bundled unrelocated. Projects using different versions of these libraries get silent classpath conflicts | Runtime `NoSuchMethodError` or behavior changes in user projects that depend on lz4/RoaringBitmap/tinylog |
| 68 | **Maven Plugin** | `PrepareMojo` calls `System.setProperty("argLine", ...)` which is global to the JVM — in multi-module reactor builds, module A's agent attachment string leaks into module B with wrong absolute paths | Wrong agent paths in multi-module builds |
| 69 | **Correctness** | `TestOrderState.save()` writes directly to target file (no temp + atomic rename) — JVM crash/OOM/kill during write leaves half-written file that silently discards all state on next load | All test history permanently lost on any crash during save |
| 70 | **Agent** | `ClassIdMap.computeIfAbsent` returns `null` when ID space is exhausted — `ConcurrentHashMap.computeIfAbsent` contract prohibits null return values, behavior is implementation-defined and may throw NPE on future JDKs | Potential NPE crash on JDK updates when ID space fills |
| 71 | **Portability** | `FileHashStore` uses `Path.toString()` as hash map key — produces `\` on Windows vs `/` on Unix. Cross-platform builds see every file as changed after switching OS | Hash-based change detection broken across platforms |

### Medium (P2)

| # | Area | Issue | Impact |
|---|------|-------|--------|
| 21 | **Duplication** | Set-cover algorithm nearly identical in `TestScorer` and `MethodScorer` | Bug fixes must be applied twice |
| 22 | **Duplication** | 15-20% boilerplate duplicated between Maven and Gradle plugins (mode resolution, change detection, hash snapshotting) | Same |
| 23 | **Performance** | Set-cover greedy in `TestScorer` is O(t²) — find-best is a linear scan per iteration; priority queue would make it O(t log t) | Slow for codebases with 10,000+ tests |
| 24 | **Memory** | `SourceFileModel` allocates `int[fileLength]` for `braceDepth` and `parenDepth` — a 500KB file burns ~4 MB; 100 parallel files = 400 MB | OOM risk in large Gradle builds with parallel test workers |
| 25 | **API Design** | `TestScorer` has 4+ constructors with subtle parameter differences; no builder pattern; no `@param` javadoc | Library users can't figure out which constructor to use |
| 26 | **API Design** | Configuration properties (`testorder.index.path`, `testorder.state.path`, etc.) scattered across files with no centralized constant class | Users discover properties by reading source; IDE autocomplete useless |
| 27 | **API Design** | Property naming inconsistent: `testorder.index.path` vs `testorder.stateFile` vs `testorder.score.newTest` | Confusing for users memorizing the API |
| 28 | **Documentation** | ~40% of public methods lack Javadoc; ~60% of parameterized methods have no `@param` tags | Library consumers get no IDE tooltip guidance |
| 29 | **Documentation** | README has no troubleshooting section — common failures (no reordering, agent errors, index corruption) undocumented | Users stuck on first failure |
| 30 | **Documentation** | No FAQ (JaCoCo compatibility, parameterized tests, Spring test slices, Kotlin Kotest). Note: backward compatibility with older test-order versions should be explicitly ignored — test and document only against current version. | Same questions filed as issues repeatedly |
| 53 | **Testing** | No example or integration test exercises `@ParameterizedTest` with test-order ordering — unknown whether parameterized tests reorder correctly, durations aggregate properly, or method-level scoring handles dynamic test names | Users discover breakage in production |
| 54 | **Testing** | No example or integration test exercises JaCoCo + test-order agent coexistence — unknown whether dual-agent attachment causes classloading errors, coverage gaps, or overhead regression | Users who need both test-order and coverage may hit silent incompatibility |
| 55 | **Testing** | No example or integration test exercises Spring Boot test slices (`@DataJpaTest`, `@WebMvcTest`, `@SpringBootTest`) — Spring context caching interacts with test ordering, potential for stale contexts or ordering-dependent failures | Spring Boot users (majority audience) are untested |
| 56 | **Testing** | No example or integration test exercises Kotlin Kotest framework — only JUnit-based Kotlin tests are covered; Kotest uses a different test engine and lifecycle | Kotest users have no verified path |
| 57 | **Testing** | No benchmarks for scoring algorithm, set-cover computation, or DependencyMap serialization — only agent hot-path is benchmarked; no stored baselines for regression detection | Performance regressions in core algorithms go undetected |
| 31 | **Documentation** | No CONTRIBUTING.md or SECURITY.md | Barrier to contribution; bytecode manipulation security model undocumented |
| 32 | **Hygiene** | Root directory cluttered with 15+ working documents: `BUG_REPORT*.md`, `research.html` (109 KB), `plan.md`, `mvn.err`, `test_harness.py` | Confusing repo first impression; wasted CI checkout bytes |
| 33 | **Hygiene** | 5 example modules + `samples/sample-shop` all build in CI | Longer build times; examples rarely change but always rebuild |
| 34 | **Hygiene** | 4 deprecated methods/records in `TestOrderState` — none called anywhere, no `@forRemoval` annotation, no removal timeline | Dead code noise; confuses contributors |
| 35 | **Build** | No Checkstyle, SpotBugs, ErrorProne, or `.editorconfig` — formatting is IDE-dependent | Inconsistent style across contributors |
| 36 | **Build** | No Maven Enforcer plugin — dependency convergence issues go undetected | Classpath conflicts at runtime |
| 37 | **Build** | No JaCoCo — zero visibility into actual test coverage | Flying blind on coverage |
| 38 | **Parser** | `SourceFileModel` is blind to annotation-processor-generated code (Lombok `@Data`, MapStruct, etc.) | False negatives in change detection for Lombok-heavy projects |
| 39 | **Observability** | When genetic optimizer silently skips (< 3 failure runs), no log message tells the user | Feature appears broken |
| 40 | **Observability** | `compactToOutcome()` silently drops corrupted run records (filters nulls, no warning) | Data loss without notification |
| 41 | **Security** | No total file size check in `DependencyMap.loadBinary()` before LZ4 decompression — a crafted 1 GB compressed payload could decompress to many GB | DoS via decompression bomb (low practical risk since file is self-generated) |
| 42 | **Release** | `release.py` leaves local git tags on push failure — subsequent retry conflicts on tag names | Requires manual `git tag -d` cleanup |
| 43 | **Release** | No dedicated GitHub Actions release workflow — entirely driven by `python release.py` run manually | No audit trail; release depends on a single person's machine state |

### Low (P3)

| # | Area | Issue | Impact |
|---|------|-------|--------|
| 44 | **Performance** | `ClassTransformer` caches (`classCallCache`, `slashToDotCache`) grow unbounded — no eviction policy | Memory leak in very large builds |
| 45 | **Performance** | `BOUNDED_GENERICS` regex limited to 4 nesting levels — `Map<A, Map<B, Map<C, Map<D, Map<E, F>>>>>` fails silently, falls back to brace scanning | Rare but possible incorrect parse |
| 46 | **Robustness** | `setFailurePruneThreshold()` has no validation — accepts negative values, NaN, Infinity | Garbage-in garbage-out for config |
| 47 | **Robustness** | `TelemetryListener` saves state only in `testPlanExecutionFinished()` — JVM crash loses all data | No workaround without periodic flushing |
| 48 | **Data** | `RunRecord` capped at `MAX_HISTORY_RUNS = 50` — defines as final field, not configurable. **Decision:** keep count-based, add thinning (keep all last 10, every 2nd from 11-25, every 4th from 26-50, every 8th beyond). Make configurable via `testorder.history.maxRuns`. | Users with slow test suites wanting more history can't adjust |
| 58 | **Change Detection** | Non-git projects (Mercurial, SVN, or no VCS) get `IOException` from `ProcessBuilder("git", ...)` — not caught in `GitChangeDetector.runGit()`, crashes instead of falling back to hash-based detection | Build fails for non-git users |
| 59 | **Scoring** | Changed classes from git that aren't in the dependency map (e.g., newly added classes not yet learned) are silently ignored in overlap scoring — under-scores affected tests | Tests for newly added production code aren't prioritized on first order run |
| 60 | **Observability** | No way for users to see which change detection mode was actually used, which classes were detected as changed, or why a specific test was scored the way it was — only `show-order` shows final scores, not the reasoning | Debugging "why didn't my test run first?" is trial and error |
| 61 | **Config** | Weights config precedence (system props > weights file > Maven config > state file > defaults) is not documented anywhere — users setting weights in POM don't know system properties override them | Confusing config behavior |
| 72 | **Performance** | `StructuralDiff.diffGitChanges` spawns 3-4 `git` processes sequentially, then one additional `git show` per changed file — 100 changed files = 103+ process creations on the critical test-startup path | Slow test startup proportional to number of changed files |
| 73 | **Performance** | Gradle `testOrderShowOrder` task calls `scorer.score()` on each comparison during sort — O(n log n) comparisons without caching, re-computing set intersection each time | `show-order` task unnecessarily slow on large test suites |
| 74 | **Correctness** | `CombinedMojo` triggers optimization on `totalRuns % optimizeEvery == 0` using `state.runs().size()` — after MAX_HISTORY_RUNS caps the list, size fluctuates and modulo check fires unpredictably or never | Optimization either runs on nearly every build or stops firing entirely |
| 75 | **Correctness** | `ProjectStructureAnalyzer.extractXmlTag` regex matches the FIRST `<groupId>` in POM — in multi-module POMs, this is often the `<parent>` element's groupId or a `<dependency>` groupId, not the project's own | Wrong source package detection from miscategorized groupId |
| 76 | **Testing** | No unit tests for `PriorityMethodOrderer`, `UsageStore`, `MethodScorer`, or any Maven Mojo except `AbstractTestOrderMojo`/`SurefireHelper` — 10+ Mojo classes tested only through E2E ITs | Major test coverage gaps in core plugin logic |
| 77 | **Testing** | `PriorityClassOrdererTest` and tests using `System.setOut`/`System.setProperty` are not thread-safe — properties leak if test hangs, no `@Timeout` guards, parallel execution corrupts shared state | Flaky tests under parallel execution |
| 78 | **CI** | No `timeout-minutes` on any CI job — hung builds consume the full 6-hour GitHub Actions default, burning credits silently | Wasted CI credits on stuck builds |
| 79 | **CI** | E2E job activates tests via `-Dtestorder.it=true` but no CI check verifies tests actually ran vs. were silently skipped due to misconfigured property forwarding | Tests may be silently skipped in CI with no detection |
| 80 | **IT** | No invoker IT fixture for multi-module projects — `ReactorContext` cross-module behavior untested end-to-end | Multi-module regressions go undetected |
| 81 | **IT** | No invoker IT fixture for `select` or `run-remaining` modes — complex argument manipulation in these modes not verified through invoker | Select/run-remaining bugs only caught manually |
| 82 | **IT** | IT fixtures hardcode JUnit 5.10.2 while parent POM uses JUnit 6.0.3 — ITs don't test against the actual JUnit version the plugin ships with | JUnit version incompatibilities masked |
| 83 | **Build** | `test-order-agent/pom.xml` uses `<executable>cp</executable>` — breaks on Windows; also spawns nested `mvn -f pom_runtime.xml package` which doesn't inherit parent settings or work in offline mode | Windows builds fail; offline builds fail |
| 84 | **Scoring** | `TestScorer.computeMedianDuration` retains stale duration entries for renamed/deleted test classes — inflates median and skews scoring for tests with no duration data | Duration model degrades over time as classes are renamed |
| 85 | **Scoring** | `ChangeDetector.toGitPrefix` returns lone `/` when sourceRoot equals projectRoot — passed to `git diff` as absolute path filter that matches nothing | Change detection silently fails for non-standard source layouts |
| 86 | **Robustness** | `ClassNameTrie.readVarInt` has no shift bounds check — 5+ continuation bytes in corrupted data cause silent integer overflow producing garbage values and wrong trie reconstruction | Corrupted index file produces wrong dependency map silently |
| 87 | **Robustness** | `DependencyMap.loadBinary` V4 member counts (`memberEntryCount`, `memberCount`) not bounds-checked via `checkSize()` — corrupt file with `memberCount = MAX_INT` causes OOM | OOM from corrupted V4 dependency index |
| 88 | **Robustness** | `IntelligentClassFilter.cacheSize` never decremented on `clearCache()` — after first cache clear, no new entries are ever cached | Filter loses caching benefit after any cache clear |
| 89 | **Scripts** | `test_harness.py` hardcodes absolute path `/Users/i560383_1/...` and uses `shell=True` in subprocess calls | Script unusable on other machines; command injection risk |
| 90 | **Scripts** | `release.py` `except` block restores file snapshots but doesn't undo `git commit` or `git tag` already created — requires manual `git reset` + `git tag -d` beyond what restoration provides | Release recovery requires manual git surgery |
| 91 | **Hygiene** | Example modules contain 15+ checked-in build artifacts (`.out`, `.test-order-state`, `.lz4` files) not covered by `.gitignore` | Repo bloat; confuses contributors |
| 92 | **Examples** | Example module POMs add the plugin with `<goal>prepare</goal>` but no `<configuration>` block — no mode, no changedClasses — running `mvn test` demonstrates nothing | Examples fail to demonstrate the product |
| 93 | **Build** | `test-order-benchmarks` module builds in CI on every run but has no tests — pure overhead | Wasted CI time |
| 95 | **Memory** | `PriorityMethodOrderer` static fields (`depMap`, `pendingState`, `changedClasses`) never cleared — in Gradle daemon, entire `DependencyMap` and `TestOrderState` persist across builds | Memory leak in Gradle daemon; stale data used if subsequent build skips order mode |

---

## Action Plan

57 tasks numbered sequentially. Each lists issue refs, target module/file, and concrete steps. Tasks within each phase are ordered so earlier tasks don't depend on later ones.

### Phase 1 — Safety & Correctness (20 tasks)

Fix bugs that cause data loss, silent corruption, or crashes. Minimal targeted fixes, no refactoring.

#### 1. Fix `TestOrderState` thread safety
**Issues:** #2, #3, #4 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Replace static `pendingBreakdowns`/`pendingStatePath` with a single `AtomicReference<PendingRunData>` holding an immutable snapshot
- Make `addRunRecord()` synchronized or use `CopyOnWriteArrayList`
- Add integration test: 2 threads doing recordBreakdown + resetPending concurrently

#### 2. Fix `UsageStore.active` tracker race
**Issues:** #62 · **Module:** `test-order-agent` · **File:** `UsageStore.java`
- Replace single `volatile ActiveTrackers active` with a `ThreadLocal<ActiveTrackers>` or `ConcurrentHashMap<Thread, ActiveTrackers>`
- Each test class's `startTestClass`/`endTestClass` must scope recording to the calling thread
- Add concurrency test: two threads doing `startTestClass(A)`/`startTestClass(B)` simultaneously verify independent tracking

#### 3. Fix `TelemetryListener` concurrent execution
**Issues:** #66 · **Module:** `test-order-junit` · **File:** `TelemetryListener.java`
- `callEndTestClass()` must pass the class name so the agent ends the correct class, not "whatever is current"
- Detect and warn if JUnit's `@Execution(CONCURRENT)` is used on test classes (not just Surefire's parallel config)

#### 4. Make file writes atomic
**Issues:** #69 · **Module:** `test-order-core` · **Files:** `TestOrderState.java`, `FileHashStore.java`, `MethodHashStore.java`, `DependencyMap.java`
- All `save()` methods: write to temp file, then `Files.move(tmp, target, ATOMIC_MOVE)`
- On load, if primary file is corrupt/missing, check for temp file as recovery source

#### 5. Use `System.nanoTime()` for duration measurement
**Issues:** #64 · **Module:** `test-order-junit` · **File:** `TelemetryListener.java`
- Replace `System.currentTimeMillis()` with `System.nanoTime()` for all elapsed-time calculations
- Convert to milliseconds only when persisting to state file

#### 6. Fix `ProjectStructureAnalyzer.listFiles()` NPE
**Issues:** #63 · **Module:** `test-order-agent` · **File:** `ProjectStructureAnalyzer.java`
- Guard `File.listFiles()` return against `null` (permission errors, dangling symlinks)
- Log warning and skip unreadable directories instead of crashing agent `premain`

#### 7. Fix `IntelligentClassFilter.isTestClass()` false positives
**Issues:** #65 · **Module:** `test-order-agent` · **File:** `IntelligentClassFilter.java`
- Replace `simpleName.contains("Test")` with `simpleName.startsWith("Test") || simpleName.endsWith("Test") || simpleName.endsWith("Tests") || simpleName.endsWith("TestCase")`
- Remove `"Mock"`, `"Stub"`, `"Spy"`, `"Fake"` from `TEST_CLASS_MARKERS` — these are not test classes
- Add test with production classes like `ContestService`, `MockitoExtension`, `ProtestHandler`

#### 8. Fix `loadJson()` robustness
**Issues:** #11, #12 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Wrap all `toInt()`/`toDouble()`/`toLong()` in try-catch returning defaults + log warning
- Wrap `Util.asMap()` casts in instanceof checks before cast
- Add test: malformed JSON with arrays-as-maps, strings-as-numbers, huge numbers

#### 9. Add state file schema version
**Issues:** #6 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Add `"schemaVersion": 1` to JSON root
- On load: reject `schemaVersion > CURRENT` with clear error message
- If schema version is missing or older, discard and start fresh with a warning (no migration logic)

#### 10. Fix overfit detection inconsistency
**Issues:** #5 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Align final overfit check with expanding-window fold evaluation
- Add unit test: synthetic runs where fold-weighted and aggregate-APFDc diverge

#### 11. Fix `CombinedMojo` optimize trigger
**Issues:** #74 · **Module:** `test-order-maven-plugin` · **File:** `CombinedMojo.java`
- Track runs-since-last-optimization in state file instead of using `runs().size() % N`
- Increment counter on each run, reset to 0 after optimization

#### 12. Fix `System.setProperty("argLine")` leak in multi-module
**Issues:** #68 · **Module:** `test-order-maven-plugin` · **File:** `AbstractTestOrderMojo.java`
- Remove `System.setProperty("argLine", ...)` — only use `project.getProperties().setProperty()`

#### 13. Fix shallow clone / single-commit failures — **auto-detect**
**Issues:** #49 · **Module:** `test-order-core` · **File:** `GitChangeDetector.java`
- Detect `HEAD~1` failure (non-zero exit code) and fall back to treating all tracked source files as changed
- Log warning: "shallow clone detected, treating all source files as changed"
- Do NOT require users to configure `fetch-depth: 0`
- Add test: verify behavior in a single-commit repo and a shallow clone

#### 14. Add git process timeout
**Issues:** #50 · **Module:** `test-order-core` · **File:** `GitChangeDetector.java`
- `process.waitFor(30, TimeUnit.SECONDS)` in `runGit()`; if timed out, `process.destroyForcibly()` and return empty set with warning

#### 15. Make returned dependency sets unmodifiable
**Issues:** #51 · **Module:** `test-order-core` · **File:** `DependencyMap.java`
- Wrap row-deduplicated sets in `Collections.unmodifiableSet()` before assigning to `depSets[]`

#### 16. Fix TelemetryListener for repeated class executions
**Issues:** #52 · **Module:** `test-order-junit` · **File:** `TelemetryListener.java`
- Replace `putIfAbsent` with a list of start times per class, or combine class name + unique test ID

#### 17. Handle non-git projects gracefully
**Issues:** #58 · **Module:** `test-order-core` · **File:** `ChangeDetector.java`
- Catch `IOException` when git is not installed or project is not a git repo
- Fall back to hash-based change detection with a clear warning

#### 18. Replace `System.out` in Mojos
**Issues:** #7 · **Module:** `test-order-maven-plugin` · **Files:** `DumpMojo.java`, `ShowOrderMojo.java`
- Change `System.out.print()` → `getLog().info()`

#### 19. Replace `e.printStackTrace()` with logging
**Issues:** #10 · **Module:** `test-order-agent` · **File:** `UsageStore.java`
- Lines 334, 354: use `AgentLogger.error()` instead

#### 20. Clean up deprecated API
**Issues:** #34 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Delete 4 deprecated methods/records that have zero callers
- Add `@forRemoval` to any deprecated methods that DO still have callers

### Phase 2 — Build & CI Hardening (13 tasks)

Prevent regressions, enforce consistency, harden the build pipeline.

#### 21. Add shade plugin relocations
**Issues:** #67 · **Module:** `test-order-core` · **File:** `pom.xml`
- Add `<relocations>` for all shaded libraries: `net.jpountz` → `…shaded.lz4`, `org.roaringbitmap` → `…shaded.roaring`, etc.
- Add `ServicesResourceTransformer` to merge `META-INF/services` files correctly

#### 22. Normalize `FileHashStore` path keys to forward slash
**Issues:** #71 · **Module:** `test-order-core` · **File:** `FileHashStore.java`
- Normalize `Path.toString()` keys to use `/` separator before persisting

#### 23. Add JaCoCo
**Issues:** #37 · **File:** root `pom.xml`
- Configure with report aggregation; minimum line coverage: 70% (core), 50% (plugins)
- Fail CI on coverage regression

#### 24. Add code quality plugins
**Issues:** #35, #36 · **File:** root `pom.xml`
- Checkstyle (Google style baseline), ErrorProne, Maven Enforcer (dependency convergence)

#### 25. Expand CI matrix
**Issues:** #18, #19 · **File:** `.github/workflows/ci.yml`
- Add JDK 21 and latest-EA to build matrix
- Add Gradle plugin integration test job
- Add Maven dependency caching (`actions/cache@v4`)

#### 26. Add `timeout-minutes` to all CI jobs
**Issues:** #78 · **File:** `.github/workflows/ci.yml`
- Add `timeout-minutes: 30` to each job

#### 27. Add CI test-count verification
**Issues:** #79 · **File:** `.github/workflows/ci.yml`
- After each test step, verify > 0 tests ran via `failsafe-summary.xml` or surefire-reports

#### 28. Fix IT fixture JUnit version
**Issues:** #82 · **Module:** `test-order-maven-plugin` · **Path:** `src/it/*/pom.xml`
- Use `${junit.version}` from `invoker.properties` instead of hardcoded 5.10.2

#### 29. Add multi-module and select/run-remaining IT fixtures
**Issues:** #80, #81 · **Module:** `test-order-maven-plugin` · **Path:** `src/it/`
- Add `multi-module-learn-mode/` fixture testing `ReactorContext`
- Add `select-mode/` and `run-remaining-mode/` fixtures
- Add `verify.groovy` scripts to all IT fixtures

#### 30. Narrow exception catching
**Issues:** #9 · **All modules**
- Audit all 20+ `catch (Exception e)` blocks; replace with `IOException`, `NumberFormatException`, `ClassCastException`
- Keep `catch (Exception)` only in top-level agent entrypoints

#### 31. Add `WeightDef` and `l2Penalty` validation
**Issues:** #13, #14 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- `WeightDef`: `checkArgument(min <= max)` in constructor
- `l2Penalty`: assert `w.length == defaults.length`

#### 32. Fix `test-order-agent` portability
**Issues:** #83 · **Module:** `test-order-agent` · **File:** `pom.xml`
- Replace `<executable>cp</executable>` with `maven-resources-plugin`
- Replace nested `mvn -f pom_runtime.xml package` with `maven-invoker-plugin` or submodule

#### 33. Skip `test-order-benchmarks` in CI test phase
**Issues:** #93 · **Module:** `test-order-benchmarks` · **File:** `pom.xml`
- Add `<skipTests>true</skipTests>` — benchmarks should only compile in CI

### Phase 3 — Architecture Refactoring (11 tasks)

Make the codebase maintainable. Larger changes that benefit from Phase 1/2 safety nets.

#### 34. Split `TestOrderState` into focused classes
**Issues:** #1 · **Module:** `test-order-core`
- `StateSerializer` — JSON/LZ4 read/write, schema versioning
- `ScoringOptimizer` — genetic algorithm, expanding-window evaluation, overfit detection
- `PendingRunCoordinator` — static thread coordination (breakdowns, state path)
- `TestOrderState` — reduced to domain model: weights, durations, failures, runs
- Move `computeAPFD*` to a standalone `APFDCalculator` utility

#### 35. Extract shared set-cover algorithm
**Issues:** #21, #23 · **Module:** `test-order-core`
- Create `SetCoverComputer<T>` parameterized by item type and coverage function
- Use from both `TestScorer` and `MethodScorer`
- Optimize with priority queue (O(t²) → O(t log t))

#### 36. Decompose Gradle plugin
**Issues:** #15, #16 · **Module:** `test-order-gradle-plugin` · **File:** `TestOrderPlugin.java`
- Split into: `TestOrderExtensionConfigurator`, `LearnModeConfigurator`, `OrderModeConfigurator`, `UtilityTaskRegistrar`
- Add file locking for state file access

#### 37. Unify plugin logic — **full Gradle parity**
**Issues:** #17, #22 · **Modules:** `test-order-core`, `test-order-maven-plugin`, `test-order-gradle-plugin`
- Extract shared mode resolution, hash snapshotting, change detection into `test-order-core` helpers
- Maven and Gradle become thin adapters
- Implement missing Gradle tasks: `select`, `run-remaining`, `optimize`, `show-order`, `dump`

#### 38. Create `TestOrderConfig` constants class
**Issues:** #26, #27 · **Module:** `test-order-core`
- All `testorder.*` property names as public `String` constants
- Standardize naming: `testorder.index.path`, `testorder.state.path`, `testorder.score.new-test`

#### 39. Add lifecycle cleanup to `PriorityMethodOrderer`
**Issues:** #20, #95 · **Module:** `test-order-junit` · **File:** `PriorityMethodOrderer.java`
- Reset static fields in `testPlanExecutionFinished()` or convert to instance-scoped `ExtensionContext.Store`
- Critical for Gradle daemon memory leak

#### 40. Reduce `StructuralDiff` git process spawning
**Issues:** #72 · **Module:** `test-order-core` · **File:** `StructuralDiff.java`
- Batch `git show` calls; combine diff/cached/ls-files into fewer invocations
- Target: ≤3 git processes regardless of changed file count

#### 41. Cache scores in Gradle `showOrder` task
**Issues:** #73 · **Module:** `test-order-gradle-plugin` · **File:** `TestOrderPlugin.java`
- Pre-compute scores into `Map<String, Double>` before sorting

#### 42. Add Spring context grouping to scoring
**Decision:** #5 · **Module:** `test-order-core` · **File:** `TestScorer.java`
- Add tie-breaker grouping tests by `@SpringBootTest`/`@ContextConfiguration` config key
- Enable via `testorder.score.springContextGrouping=true`

#### 43. Add variance-aware EMA smoothing
**Decision:** #6 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- Track running CV of test durations; when CV > threshold, lower effective alpha
- Configurable via `testorder.score.ema.varianceThreshold` (default 0.5)
- Add test: alternating fast/slow durations → verify dampened oscillation

#### 44. Implement run-history thinning
**Decision:** #7 · **Module:** `test-order-core` · **File:** `TestOrderState.java`
- On save, thin old `RunRecord` entries (backup-rotation): keep all last 10, every 2nd 11-25, every 4th 26-50, every 8th beyond
- Configurable via `testorder.history.maxRuns` (default 50)
- Add test: verify thinning for various history sizes

### Phase 4 — Documentation & User Experience (8 tasks)

Make adoption frictionless. Write docs based on verified behavior from compatibility tests.

#### 45. Add troubleshooting section to README
**Issues:** #29 · **File:** `README.md`
- "Tests aren't reordering" — check index, mode, changed classes
- "Agent errors" — classpath isolation, JDK mismatch
- "State file corruption" — delete and re-learn
- "Empty dependency index" — source package detection

#### 46. Add compatibility integration tests
**Issues:** #53, #54, #55, #56 · **Modules:** example modules, CI
- **Parameterized tests:** `@ParameterizedTest` with `@CsvSource`/`@ValueSource`/`@MethodSource`
- **JaCoCo coexistence:** Maven profile with both agents
- **Spring Boot test slices:** `@DataJpaTest`, `@WebMvcTest`, `@SpringBootTest`
- **Kotlin Kotest:** Kotest-based example module
- Wire all into CI

#### 47. Add FAQ to README
**Issues:** #30 · **File:** `README.md` · **Prerequisite:** task 46
- JaCoCo compatibility, parameterized tests, Spring slices, Kotlin Kotest
- Backward compatibility with older test-order versions: explicitly ignored

#### 48. Add CONTRIBUTING.md and SECURITY.md
**Issues:** #31
- CONTRIBUTING: dev setup (JDK 17+, Maven 3.9+), code style, PR process
- SECURITY: bytecode instrumentation model, vulnerability reporting

#### 49. Add Javadoc to core API
**Issues:** #28 · **Modules:** `test-order-core`, `test-order-junit`
- Priority: `TestScorer`, `TestSelector`, `DependencyMap`, `PriorityClassOrderer`

#### 50. Add `TestScorer.Builder`
**Issues:** #25 · **Module:** `test-order-core` · **File:** `TestScorer.java`
- Builder with required vs optional parameters; deprecate multi-constructor overloads

#### 51. Document configuration precedence
**Issues:** #61 · **File:** `README.md`
- "Configuration Precedence" section with numbered priority list

#### 52. Add change-detection debug logging
**Issues:** #60 · **Modules:** `test-order-core`, `test-order-junit`
- INFO: detection mode, changed class count; DEBUG: per-test scoring breakdown via `-Dtestorder.debug=true`

### Phase 5 — Hygiene & Release Engineering (5 tasks)

Professional project presentation, safe releases.

#### 53. Clean root directory
**Issues:** #32
- `BUG_REPORT*.md`, `plan.md`, `FIELD_TRACKING_IMPLEMENTATION.md` → `docs/internal/`
- `research.html`, `research.md` → `docs/research/`
- `test_harness.py`, `demo-petclinic.sh` → `tools/`
- `.gitignore` `mvn.err`, `mvn.out`; add `.editorconfig`

#### 54. Consolidate example modules
**Issues:** #33, #91, #92
- Keep `test-order-example` (Maven) + `test-order-example-gradle` (Gradle)
- Merge other example modules into `test-order-example`
- Add `<configuration><mode>combined</mode></configuration>` to example POMs
- Add README per example; clean up checked-in artifacts; add module `.gitignore`

#### 55. Harden release process
**Issues:** #8, #42, #43, #90
- Add `--dry-run` flag to `release.py`
- Maven staging: deploy → verify → promote
- In except block: undo `git commit` + `git tag`, clean local tags on push failure
- GitHub Actions release workflow triggered by tag push

#### 56. Add decompression bomb guard
**Issues:** #41 · **Module:** `test-order-core` · **File:** `DependencyMap.java`
- Check `Files.size(indexFile) < 1 GB` before LZ4 decompression

#### 57. Add scoring/serialization benchmarks
**Issues:** #57 · **Module:** `test-order-benchmarks`
- JMH: `TestScorer.score()`, `DependencyMap.load()`, `SetCover`, `TestOrderState` round-trip
- Store baselines in `test-order-benchmarks/baselines/`

---

## Design Decisions (Resolved)

| # | Question | Decision |
|---|----------|----------|
| 1 | Shallow clone strategy | **Auto-detect.** Fall back to all-files-changed when `HEAD~1` fails. |
| 2 | Jenetics dependency | **Keep as required.** ~2 MB cost is acceptable. |
| 3 | State file locking | **Decide in Phase 3.** Options: `FileLock`, lock file, merge strategy. |
| 4 | Gradle feature parity | **Full.** All 10 Maven goals as Gradle tasks. |
| 5 | Spring context caching | **Fix.** Group tests by context config via scoring tie-breaker. |
| 6 | EMA for flaky tests | **Variance-aware.** Lower alpha when CV > threshold. |
| 7 | MAX_HISTORY_RUNS | **Count-based + thinning** (backup rotation). Configurable. |

---

## Issue → Task Cross-Reference

| Issues | Task | Phase |
|--------|------|-------|
| #2, #3, #4 | 1 | 1 |
| #62 | 2 | 1 |
| #66 | 3 | 1 |
| #69 | 4 | 1 |
| #64 | 5 | 1 |
| #63 | 6 | 1 |
| #65 | 7 | 1 |
| #11, #12 | 8 | 1 |
| #6 | 9 | 1 |
| #5 | 10 | 1 |
| #74 | 11 | 1 |
| #68 | 12 | 1 |
| #49 | 13 | 1 |
| #50 | 14 | 1 |
| #51 | 15 | 1 |
| #52 | 16 | 1 |
| #58 | 17 | 1 |
| #7 | 18 | 1 |
| #10 | 19 | 1 |
| #34 | 20 | 1 |
| #67 | 21 | 2 |
| #71 | 22 | 2 |
| #37 | 23 | 2 |
| #35, #36 | 24 | 2 |
| #18, #19 | 25 | 2 |
| #78 | 26 | 2 |
| #79 | 27 | 2 |
| #82 | 28 | 2 |
| #80, #81 | 29 | 2 |
| #9 | 30 | 2 |
| #13, #14 | 31 | 2 |
| #83 | 32 | 2 |
| #93 | 33 | 2 |
| #1 | 34 | 3 |
| #21, #23 | 35 | 3 |
| #15, #16 | 36 | 3 |
| #17, #22 | 37 | 3 |
| #26, #27 | 38 | 3 |
| #20, #95 | 39 | 3 |
| #72 | 40 | 3 |
| #73 | 41 | 3 |
| D#5 | 42 | 3 |
| D#6 | 43 | 3 |
| D#7, #48 | 44 | 3 |
| #29 | 45 | 4 |
| #53–#56 | 46 | 4 |
| #30 | 47 | 4 |
| #31 | 48 | 4 |
| #28 | 49 | 4 |
| #25 | 50 | 4 |
| #61 | 51 | 4 |
| #60 | 52 | 4 |
| #32 | 53 | 5 |
| #33, #91, #92 | 54 | 5 |
| #8, #42, #43, #90 | 55 | 5 |
| #41 | 56 | 5 |
| #57 | 57 | 5 |

**Issues not assigned to a specific task** (low-priority, fix opportunistically):
#24, #38, #39, #40, #44, #45, #46, #47, #59, #70, #75, #76, #77, #84, #85, #86, #87, #88, #89

---

## Effort Estimates

| Phase | Scope | Tasks | Files |
|-------|-------|-------|-------|
| **1** | Safety & Correctness | 1–20 | ~25 |
| **2** | Build & CI Hardening | 21–33 | ~10 + build config + CI |
| **3** | Architecture Refactoring | 34–44 | ~35 (major refactor) |
| **4** | Documentation & Testing | 45–52 | ~12 + new example modules |
| **5** | Hygiene & Release | 53–57 | ~25 moves + CI workflow |
