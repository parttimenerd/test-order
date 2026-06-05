# Bug Hunt Report

Audit conducted 2026-06-05 on the reactor class-id map implementation and related code.

---

## Bugs Fixed

### BUG-1: SourceRootScanner — hidden files produced malformed FQNs

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** A file like `.foo.java` would be scanned, stripped of `.java`, and registered as FQN `.foo` — a malformed, dot-leading name that would never match any real class.  
**Fix:** Added explicit check `if (name.startsWith(".")) continue;` inside `visitFile`.  
**Test:** `SourceRootScannerTest.hiddenFilesAreSkipped`

### BUG-2: SourceRootScanner — hidden directories were descended into

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** Directories like `.git/refs/heads/` or `.archived/pkg/` would be traversed, and any `.java` files inside would be scanned. In a worst case, someone pointing the scanner at a project root would register thousands of junk FQNs.  
**Fix:** Added `preVisitDirectory` override in the new `Files.walkFileTree` implementation that returns `SKIP_SUBTREE` for dot-prefixed directories (except the root itself).  
**Test:** `SourceRootScannerTest.hiddenDirectoriesAreSkipped`, `.rootItselfMayBeHidden`

### BUG-3: SourceRootScanner — `Files.walk` aborted entire scan on symlink cycle

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** `Files.walk` throws `FileSystemLoopException` on a symlink cycle; the catch block silently returned whatever had been collected so far, dropping all files not yet visited. Similar failure modes for broken symlinks or permission-denied directories.  
**Fix:** Rewrote scanner to use `Files.walkFileTree` with `SimpleFileVisitor`. `visitFileFailed` returns `CONTINUE` so per-file errors never abort the scan. The outer `IOException` catch is the last resort for catastrophic failures.  
**Test:** `SourceRootScannerTest.brokenSymlinkDoesNotAbortScan`

### BUG-4: ReactorContext — reactor root not normalized

**File:** `test-order-maven-plugin/…/ReactorContext.java` line 68  
**Symptom:** `session.getTopLevelProject().getBasedir().toPath()` was used without `.normalize()`. If the path contained `..` segments, derived paths (`<reactorRoot>/.test-order/class-id-map.bin`) would differ in surface form between modules, potentially tripping up `Path.equals` comparisons and making the FileLock identity inconsistent across `mvn -T` threads.  
**Fix:** Added `.normalize()` call to the reactor root assignment (both the `mmDir` and fallback branches).

### BUG-5: CollectorLifecycleParticipant — reactor root used wrong source in `-pl` builds

**File:** `test-order-maven-plugin/…/CollectorLifecycleParticipant.java` line 145  
**Symptom:** `prepareReactorClassIdMap` used `session.getTopLevelProject().getBasedir()` to determine the reactor root. In a `mvn -pl <submodule>` invocation from the reactor root, `getTopLevelProject()` returns the *selected* submodule (not the reactor root). This caused the lifecycle participant to write `class-id-map.bin` to a DIFFERENT location than `ReactorContext` (which correctly uses `getMultiModuleProjectDirectory()`), so the per-module prepares would not find the pre-allocated IDs.  
**Fix:** Changed `prepareReactorClassIdMap` to use `session.getRequest().getMultiModuleProjectDirectory()` (with fallback to `getTopLevelProject()` for safety), matching the logic already in `ReactorContext`.

### BUG-6: `configureOfflineLearnMode` deleted the reactor-wide class-id map on re-instrumentation

**File:** `test-order-maven-plugin/…/AbstractTestOrderMojo.java` lines 1673-1678  
**Symptom:** When a module had a stale instrumentation state (mapping file exists but no backup), the code deleted the mapping file before calling `runOfflineInstrumentation`. In a multi-module build, the mapping file is the REACTOR-WIDE `class-id-map.bin` shared by all modules. Deleting it destroyed every other module's pre-allocated IDs, causing cross-module edges to be either mis-attributed or dropped.  
**Fix:** Removed the `Files.deleteIfExists(mappingFile)` call. `runOfflineInstrumentation` already handles the stale-marker case via `setIgnoreMarker(true)` and correctly loads the existing map before instrumenting.

### BUG-7: PrepareMojo had a duplicate `REACTOR_MAP_INTRA_JVM_LOCK` shadowing the parent's lock

**File:** `test-order-maven-plugin/…/PrepareMojo.java` line 115  
**Symptom:** `PrepareMojo` declared its own `private static final Object REACTOR_MAP_INTRA_JVM_LOCK` while `AbstractTestOrderMojo` (the parent) also had a `static final Object REACTOR_MAP_INTRA_JVM_LOCK`. Since Java `static` fields belong to the declaring class, `PrepareMojo.REACTOR_MAP_INTRA_JVM_LOCK` and `AbstractTestOrderMojo.REACTOR_MAP_INTRA_JVM_LOCK` were **two different objects**. `PrepareMojo.performDeferredOfflineInstrumentation` and `AbstractTestOrderMojo.runOfflineInstrumentation` therefore did NOT mutually exclude each other. In a `mvn -T` build, a parallel `prepare` from module A (using the child-class lock) could race against `runOfflineInstrumentation` from module B (using the parent-class lock), corrupting the ClassIdMap singleton.  
**Fix:** Removed the duplicate field from `PrepareMojo` entirely. `synchronized (REACTOR_MAP_INTRA_JVM_LOCK)` in `PrepareMojo` now resolves to `AbstractTestOrderMojo.REACTOR_MAP_INTRA_JVM_LOCK` (the shared one).

### BUG-8: `InstrumentMojo` overwrote reactor-wide map with only single-module data

**File:** `test-order-maven-plugin/…/InstrumentMojo.java` lines 147-162 (before fix)  
**Symptom:** `instrumentor.instrument(classesDir)` returned a `ClassIdMapping` snapshot containing ONLY the IDs allocated during that one module's instrumentation. `mapping.save(mappingFile)` then wrote that partial snapshot to the reactor-wide `class-id-map.bin`, overwriting every other module's IDs with a single-module view. In a multi-module build with `mvn -T`, two concurrent `InstrumentMojo` executions would also race on the singleton ClassIdMap without any lock.  
**Fix:** Added the same `synchronized (REACTOR_MAP_INTRA_JVM_LOCK)` + `FileLock` pattern used by `PrepareMojo` and `runOfflineInstrumentation`. Within the lock, load the existing reactor map into the singleton before instrumenting, then save `ClassIdMapping.fromClassIdMap(singleton, ...)` (the full union, not just this module's additions). Removed the now-unused `ClassIdMapping` import.

### BUG-9: `IndexCollectorServer.hasSource` — package prefix matched prefix-of-package-name

**File:** `test-order-core/…/IndexCollectorServer.java` method `hasSource`  
**Symptom:** `className.startsWith(prefix)` without a boundary check accepted `com.example2.Foo` as matching prefix `com.example`. In projects with package names sharing a common prefix (e.g. `com.example` and `com.example2`), classes from `com.example2` would be incorrectly retained in the dependency index even when `includePackages=com.example` was set. This would silently inflate the dependency graph and cause unnecessary test reruns.  
**Fix:** Changed the match condition to require that the character immediately after the prefix is `.`, `$`, or end-of-string — i.e., a real package/class boundary.  
**Test:** `IndexCollectorServerTest.packagePrefixFilterRespectsBoundary`, `.packagePrefixFilterKeepsInnerClasses`

### BUG-10: `processFallbackFile` leaked `claimedFile` on `readAllLines` IOException

**File:** `test-order-core/…/IndexCollectorServer.java` line 843 (before fix)  
**Symptom:** If `Files.readAllLines(claimedFile)` threw (e.g. encoding error, transient I/O), the exception propagated out of `processFallbackFile` without deleting `claimedFile`. The `.processing` temp file would remain on disk forever (or until manually cleaned), blocking future calls to `processFallbackFile` from picking up and retrying the original fallback file (since it had already been renamed away).  
**Fix:** Wrapped `readAllLines` in a try-catch that calls `Files.deleteIfExists(claimedFile)` before rethrowing.

---

## Bugs Fixed (Session 2 — 2026-06-05)

### BUG-11: `testorder.extensionActive` produced spurious "Unknown property" warning

**File:** `test-order-maven-plugin/…/MavenPluginConfigKeys.java` line 229  
**Symptom:** The lifecycle extension sets `testorder.extensionActive` in Maven's user properties to signal to the plugin that the extension is active. This internal coordination key was not in `ALL_KNOWN_KEYS` and not in the skip list, so every build logged `[WARNING] Unknown property 'testorder.extensionActive'`.  
**Fix:** Added `key.equals("testorder.extensionActive")` to the skip list in `findUnknownProperties`.

### BUG-12: `SelectMojo` ignored user's `-Dtest=` filter, producing misleading "not selected" warnings

**File:** `test-order-maven-plugin/…/SelectMojo.java`  
**Symptom:** When a user passed `-Dtest=CartTest` alongside `test-order:affected`, `SelectMojo` still ran its selection logic and warned "N tests were NOT selected and will NOT run" — even though the user's explicit filter would override test-order's selection via Maven property precedence. This was both confusing and wasted time.  
**Fix:** Added a guard at the top of `execute()`: if `session.getUserProperties().getProperty("test")` is non-blank, skip selection entirely and log "Skipping selection — -Dtest=X filter active." Consistent with the guard already in `PrepareMojo.executeOrderMode()`.

### BUG-13: `AutoMojo` ignored user's `-Dtest=` filter in auto-select mode

**File:** `test-order-maven-plugin/…/AutoMojo.java`  
**Symptom:** Same issue as BUG-12 but in `test-order:auto` mode. If a user passed `-Dtest=CartTest` with `test-order:auto test`, the auto-selection would run, potentially produce "Remaining tests written to..." messages, and try to override Surefire's test filter.  
**Fix:** Same guard pattern: check `session.getUserProperties().getProperty("test")` before running the auto workflow; skip and return early if the user specified an explicit test filter.

### BUG-14 (minor): `pendingRunCompleted` in `TestOrderState` not declared volatile

**File:** `test-order-core/…/TestOrderState.java` line 495  
**Symptom:** `addRunRecord()` (which sets `pendingRunCompleted = true`) is `synchronized`, but `toPersistedRoot()` reads `pendingRunCompleted` outside any lock and `afterSave()` writes it outside any lock. Under concurrent saves (theoretically possible in tests or benchmarks), a thread could see a stale `false` value, causing failure-score decay to be skipped for a run.  
**Fix:** Changed `private boolean pendingRunCompleted` to `private volatile boolean pendingRunCompleted`.

### BUG-15: `TieredSelectMojo` also ignored user's `-Dtest=` filter

**File:** `test-order-maven-plugin/…/TieredSelectMojo.java`  
**Symptom:** Same as BUG-12/13 but in `test-order:tiered-select`. When `-Dtest=CartTest` was specified alongside `tiered-select`, the mojo still ran its tier-selection logic, writing tier files and running `configureIncludes`, even though the user's filter would override the selection.  
**Fix:** Same guard pattern applied: skip tiered selection entirely if `session.getUserProperties().getProperty("test")` is non-blank.

---

## Bugs Fixed (Session 3 — 2026-06-05)

### BUG-16: `performDeferredOfflineInstrumentation` in `PrepareMojo` did not register backup for session-end restoration

**File:** `test-order-maven-plugin/…/PrepareMojo.java` line 504 (before fix)  
**Symptom:** When deferred offline instrumentation ran (i.e., `configureOfflineLearnMode` set `testorder.offline.pending=true` because classes weren't compiled yet, and `PrepareMojo.performDeferredOfflineInstrumentation` ran them at `process-test-classes`), the resulting `classes-backup` directory was added to `AbstractTestOrderMojo.pendingRestores` (a static set that nothing reads) but was NOT registered via `registerPendingRestoreInSession`. As a result:
1. `ClassBackupRestorer.pendingBackups` never included this path, so `ClassBackupRestorer.restoreAll()` at session end didn't restore the bytecode.
2. No JVM shutdown hook was installed for this path.
The consequence: after a deferred-instrumentation learn run, the instrumented bytecode was left on disk. The next `mvn test` (without `clean`) would encounter `UsageStore` call-sites injected into compiled classes and fail with `NoClassDefFoundError` when non-test plugins (e.g. annotation processors) loaded those classes outside the test classpath.  
**Fix:** Added `registerPendingRestoreInSession(backupDir)` and `registerPendingRestoreInSession(testBackupDir)` after the existing `pendingRestores.add()` call, matching the pattern in `runOfflineInstrumentation` (lines 1955–1956).

### BUG-17: `RunTieredMojo` also ignored user's `-Dtest=` filter

**File:** `test-order-maven-plugin/…/RunTieredMojo.java`  
**Symptom:** Same as BUG-12/13/15 but in `test-order:run-tiered`. When `-Dtest=CartTest` was specified alongside `run-tiered`, the mojo still ran full tiered selection and configured Surefire's include list, even though Maven's user property `test=CartTest` would override the selection anyway. This wasted time computing tier assignments and wrote unnecessary tier files.  
**Fix:** Same guard pattern: skip run-tiered selection entirely and return early if `session.getUserProperties().getProperty("test")` is non-blank.

---

## Bugs Fixed (Session 4 — 2026-06-05)

### BUG-18: `OrderConstraintManager.applyMustNotPrecede` missed cascading MUST_NOT_PRECEDE violations

**File:** `test-order-core/…/ops/detection/OrderConstraintManager.java` lines 126-142  
**Symptom:** The greedy swap loop checked adjacent pairs but did not decrement `i` after a swap. If the element now at `i+1` was also a victim of the same polluter (e.g., both `P→V1` and `P→V2` are MUST_NOT_PRECEDE pairs and V2 was swapped into the slot after P), the second violation was never detected or fixed.  
**Fix:** Added `i--` after every swap so the loop re-checks position `i` with its new neighbor before advancing.  
**Tests:** `OrderConstraintManagerTest.mustNotPrecedeRecheckAfterSwapCatchesNewViolation`, `.mustNotPrecedeAtEndMovesVictimBeforePolluter`

### BUG-19: `DashboardMojo` passed relative-path `outPath.getParent()` to `DashboardWorkflow` instead of absolute

**File:** `test-order-maven-plugin/…/DashboardMojo.java` line 92  
**Symptom:** When a user overrides `testorder.dashboard.output` with a relative path like `dashboard/index.html`, `outPath.getParent()` returns `Path("dashboard")` (relative). However, `DashboardWorkflow` stores this as `outputDir` and calls `outputDir.resolve("index.html")`, which produces a path relative to the JVM working directory rather than the project directory. If `outputDir` was null (bare filename like `index.html`), it caused a `NullPointerException`.  
**Fix:** Changed `outPath.getParent()` to `outPath.toAbsolutePath().getParent()` (consistent with the null-safe check already on line 61).

### BUG-21: `TestScorer.explain()` omitted complexity bonus in set-cover mode, diverging from `score()`

**File:** `test-order-core/…/TestScorer.java` lines 519-540  
**Symptom:** `score()` computes the complexity bonus in both the set-cover path AND the non-set-cover path (lines 374-387 and 389-407 respectively). However `explain()` only computed the complexity bonus in the non-set-cover `else` branch (lines 533-539). When set-cover mode was active and `changeComplexity` data was non-empty, `explain()` reported a lower total score than `score()` actually used for ordering. The `show` command's score breakdown was therefore misleading — it showed a lower score than what drove the actual test sequence.  
**Fix:** Added the complexity bonus calculation to the set-cover branch in `explain()`, mirroring lines 380-386 of `score()`.

### BUG-20: `TestOrderState.save` / `saveAggregatedFork` did not invalidate the load cache on IOException

**File:** `test-order-core/…/TestOrderState.java` lines 783-811  
**Symptom:** If `StateSerializer.save` threw an `IOException` (e.g., disk full, `moveIntoPlace` failed), the `STATE_LOAD_CACHE.removeIf` call was never reached. The mutated in-memory object (with `pendingRunCompleted = true` from the previous `addRunRecord` call, but not yet persisted) remained cached under the old file key. The next `load()` with the same file metadata returned the stale object. On the next successful save, `toPersistedRoot(applyDecay=true)` applied a second decay round for the same run, causing old failure scores to decay twice as fast.  
**Fix:** Wrapped both `StateSerializer.save` calls in try/finally so the cache invalidation always runs, whether the save succeeded or failed.

---

## Bugs Fixed (Session 5 — 2026-06-05)

### BUG-22: `ChangeAnalysis` bytecode-augmentation filter used `startsWith` without package-boundary check

**File:** `test-order-core/…/ops/workflows/ChangeAnalysis.java` line 392 (before fix)  
**Symptom:** The bytecode-dependency-augmentation filter applied `includePackages` using `dep::startsWith` with no boundary check. A user configuring `includePackages=com.example` would incorrectly let augmented edges to `com.example2.Service` pass through the filter (same root cause as BUG-9 in `IndexCollectorServer.hasSource`). This silently inflated the dependency graph with cross-package edges, causing tests to be unnecessarily re-run whenever `com.example2` classes changed.  
**Fix:** Changed the filter lambda to require a `.` or `$` boundary character immediately after the matched prefix (or exact-length match), mirroring the fix already in `IndexCollectorServer.hasSource`.

---

## Runtime Testing Performed (Session 2)

- **Single-module demo-shop**: learn pass → order pass → code change → order mode correctly boosts CartTest and InvoiceTest over ProductTest.
- **Synthetic history**: three consecutive order runs; `show` command confirms state is healthy (`runsSinceLearn=3`).
- **Select mode**: `topN=1` selects 1 test; `run-remaining` correctly runs the deferred 2.
- **`-Dtest=` filter interaction**: verified that `-Dtest=CartTest` with order mode, affected mode, and auto mode all correctly skip reordering/selection and let Surefire handle the explicit filter.
- **All 250 unit tests pass** after session-2 fixes.
- **All ITs pass**: cross-module-tracking, diamond-modules, transitive-modules, select-mode.

---

## Areas Audited With No Bugs Found

- `ClassIdMap.bulkLoadClasses` / `bulkLoadMembers` cursor advancement — correct (always called inside `synchronized(REACTOR_MAP_INTRA_JVM_LOCK)` + FileLock, so no concurrent access)
- `ClassIdMapping.fromClassIdMap` / `save` / `load` round-trip — correct
- `IndexCollectorServer.resolveMemberIds` — correct 0-based adjusted indexing
- `BitsetTracker.recordMember` — correct `memberId - MEMBER_ID_OFFSET` adjustment
- `OfflineRuntimeBootstrap.init` — correct synchronized guard with volatile flag
- `IndexCollectorServer.stopAndMerge` spin-wait for handler threads — correct
- `PersistenceSupport.withFileLock` — correct JVM-wide + cross-JVM locking
- `CollectorLifecycleParticipant.prepareReactorClassIdMap` isolation (uses `createForBenchmark()`, not singleton) — correct
- `drainCollectors` session-property bridge — correct multi-module drain ordering
- `DependencyMap.mergeFromAgent` FileLock usage — correct
- `ClassIdMap.getOrRegisterClass` ConcurrentHashMap.computeIfAbsent atomicity — correct
- `ReactorContext` same-module path for single-module builds — correct (reactor root == project root)
- `TestOrderState` run-history compaction (`thinRunHistory`) — correct temporal distribution
- `IndexCompactionOperation.compact` — partial `.deps` file reads handled gracefully
- `DependencyMap.aggregateFromDepsDirectory` comment-filter inconsistency — not a real bug (.deps files are machine-written; no `#` lines in practice)
- `GitChangeDetector.readRawLine` — correct; `continue` in for-loop advances past multi-char sequences intentionally
- `SourceFileModel.extractAdditionalFieldNames` angleDepth loop — correct; `i++` + `continue` is the standard Java pattern for consuming multi-char sequences
- `SelectOperation` seed computation via `hashCode()` — correct (deterministic seed within JVM run, negative values are fine for `Random` seeding)
- `CiSummaryWriter` GitHub PR ref parsing `split("/")[2]` — safe; `startsWith("refs/pull/")` guarantees ≥3 parts
- `StaticCallGraphAnalyzer` / `StructuralChangeAnalyzer` / `SelectiveLearnSupport` — no bugs found
- `OrderConstraintManager.buildConstrainedOrder` topological sort — correct (Kahn's algorithm with cycle detection)
- `DependencyMap.aggregate` concurrent read protection — IOException per-file catch
- `RunRemainingMojo` empty/missing file handling — correct (sets `skipTests=true`)
- `AggregateMojo` no-deps fallback — correct (succeeds if collector already wrote index)
- `PersistenceSupport.cleanupStaleLock` TOCTOU — safe (FileLock holds even after file deletion)


## BUG-90: `StateConfiguration.runsSinceLearn` int overflow silences weight optimizer permanently

**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateConfiguration.java`  
**Symptom:** After ~2.1 billion test-order runs (achievable by long-running CI pipelines over years), `incrementRunsSinceLearn()` wraps `int` from `Integer.MAX_VALUE` to `Integer.MIN_VALUE`. `AutoWorkflow.optimizeIfDue` checks `state.runsSinceLearn() <= 0`, which then evaluates to `true` permanently, disabling the periodic weight optimizer forever without any log message.  
**Fix:** Saturate at `Integer.MAX_VALUE` instead of wrapping.  
**Regression test:** `StateConfigurationTest.incrementRunsSinceLearnSaturesAtMaxValue`


## BUG-91: `StateConfiguration.emaVarianceThreshold` Javadoc inverts the actual adaptive-alpha direction

**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateConfiguration.java`  
**Symptom:** The Javadoc on `emaVarianceThreshold()` stated "When variance is high, alpha is *increased* to track real changes quickly." In reality, `DurationTracker.adaptiveAlpha` *reduces* alpha when the relative standard deviation exceeds the threshold (more aggressive smoothing to damp noise). Any developer reading the Javadoc would configure the threshold backwards — e.g., raising it to get less noise, when raising it actually means fewer tests get dampened at all.  
**Fix:** Corrected the Javadoc to say: "When the relative standard deviation exceeds this threshold, the effective EMA alpha is *reduced* (more aggressive smoothing) to damp out measurement noise."  
**Regression tests:** `DurationTrackerTest.highVarianceDampensAlphaForMoreSmoothing`, `DurationTrackerTest.stableVarianceUsesBaseAlpha`, `DurationTrackerTest.negativeOrZeroDurationIsIgnored`

## BUG-92: `CiSummaryWriter.findExistingCommentId` returns wrong ID when comment object contains nested sub-objects with `"id"` fields

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/CiSummaryWriter.java`  
**Symptom:** The GitHub API response for listing PR comments includes nested sub-objects like `"user": {"id": 789, "login": "bot"}`. The old backward-walk scanned from the marker position towards the beginning of the string using `lastIndexOf('{')`, finding the innermost `{` first. That innermost `{` belonged to the `"user"` object, whose `"id"` field was extracted — returning the user's numeric ID (e.g. 789) instead of the comment's ID (e.g. 12345). Consequence: the subsequent PATCH request would target the wrong endpoint, failing with a 404 or silently updating the wrong resource.  
**Fix:** Replaced the backward-walk heuristic with a proper forward scan over the JSON array. Each top-level comment object is parsed with full depth-tracking (using explicit brace counting and string-escape handling). The `"id"` field is only extracted when the parser is at depth 1 (directly inside the comment object), so nested sub-objects are invisible to the ID extraction.  
**Regression test:** `CiSummaryWriterTest.findExistingCommentId_nestedUserIdNotPickedInsteadOfCommentId`

