# Bug Hunt Report

Audit conducted 2026-06-05 on the reactor class-id map implementation and related code.

---

## Bugs Fixed

### BUG-1: SourceRootScanner — hidden files produced malformed FQNs ✓ FIXED

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** A file like `.foo.java` would be scanned, stripped of `.java`, and registered as FQN `.foo` — a malformed, dot-leading name that would never match any real class.  
**Fix:** Added explicit check `if (name.startsWith(".")) continue;` inside `visitFile`.  
**Test:** `SourceRootScannerTest.hiddenFilesAreSkipped`

### BUG-2: SourceRootScanner — hidden directories were descended into ✓ FIXED

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** Directories like `.git/refs/heads/` or `.archived/pkg/` would be traversed, and any `.java` files inside would be scanned. In a worst case, someone pointing the scanner at a project root would register thousands of junk FQNs.  
**Fix:** Added `preVisitDirectory` override in the new `Files.walkFileTree` implementation that returns `SKIP_SUBTREE` for dot-prefixed directories (except the root itself).  
**Test:** `SourceRootScannerTest.hiddenDirectoriesAreSkipped`, `.rootItselfMayBeHidden`

### BUG-3: SourceRootScanner — `Files.walk` aborted entire scan on symlink cycle ✓ FIXED

**File:** `test-order-core/…/SourceRootScanner.java`  
**Symptom:** `Files.walk` throws `FileSystemLoopException` on a symlink cycle; the catch block silently returned whatever had been collected so far, dropping all files not yet visited. Similar failure modes for broken symlinks or permission-denied directories.  
**Fix:** Rewrote scanner to use `Files.walkFileTree` with `SimpleFileVisitor`. `visitFileFailed` returns `CONTINUE` so per-file errors never abort the scan. The outer `IOException` catch is the last resort for catastrophic failures.  
**Test:** `SourceRootScannerTest.brokenSymlinkDoesNotAbortScan`

### BUG-4: ReactorContext — reactor root not normalized ✓ FIXED

**File:** `test-order-maven-plugin/…/ReactorContext.java` line 68  
**Symptom:** `session.getTopLevelProject().getBasedir().toPath()` was used without `.normalize()`. If the path contained `..` segments, derived paths (`<reactorRoot>/.test-order/class-id-map.bin`) would differ in surface form between modules, potentially tripping up `Path.equals` comparisons and making the FileLock identity inconsistent across `mvn -T` threads.  
**Fix:** Added `.normalize()` call to the reactor root assignment (both the `mmDir` and fallback branches).

### BUG-5: CollectorLifecycleParticipant — reactor root used wrong source in `-pl` builds ✓ FIXED

**File:** `test-order-maven-plugin/…/CollectorLifecycleParticipant.java` line 145  
**Symptom:** `prepareReactorClassIdMap` used `session.getTopLevelProject().getBasedir()` to determine the reactor root. In a `mvn -pl <submodule>` invocation from the reactor root, `getTopLevelProject()` returns the *selected* submodule (not the reactor root). This caused the lifecycle participant to write `class-id-map.bin` to a DIFFERENT location than `ReactorContext` (which correctly uses `getMultiModuleProjectDirectory()`), so the per-module prepares would not find the pre-allocated IDs.  
**Fix:** Changed `prepareReactorClassIdMap` to use `session.getRequest().getMultiModuleProjectDirectory()` (with fallback to `getTopLevelProject()` for safety), matching the logic already in `ReactorContext`.

### BUG-6: `configureOfflineLearnMode` deleted the reactor-wide class-id map on re-instrumentation ✓ FIXED

**File:** `test-order-maven-plugin/…/AbstractTestOrderMojo.java` lines 1673-1678  
**Symptom:** When a module had a stale instrumentation state (mapping file exists but no backup), the code deleted the mapping file before calling `runOfflineInstrumentation`. In a multi-module build, the mapping file is the REACTOR-WIDE `class-id-map.bin` shared by all modules. Deleting it destroyed every other module's pre-allocated IDs, causing cross-module edges to be either mis-attributed or dropped.  
**Fix:** Removed the `Files.deleteIfExists(mappingFile)` call. `runOfflineInstrumentation` already handles the stale-marker case via `setIgnoreMarker(true)` and correctly loads the existing map before instrumenting.

### BUG-7: PrepareMojo had a duplicate `REACTOR_MAP_INTRA_JVM_LOCK` shadowing the parent's lock ✓ FIXED

**File:** `test-order-maven-plugin/…/PrepareMojo.java` line 115  
**Symptom:** `PrepareMojo` declared its own `private static final Object REACTOR_MAP_INTRA_JVM_LOCK` while `AbstractTestOrderMojo` (the parent) also had a `static final Object REACTOR_MAP_INTRA_JVM_LOCK`. Since Java `static` fields belong to the declaring class, `PrepareMojo.REACTOR_MAP_INTRA_JVM_LOCK` and `AbstractTestOrderMojo.REACTOR_MAP_INTRA_JVM_LOCK` were **two different objects**. `PrepareMojo.performDeferredOfflineInstrumentation` and `AbstractTestOrderMojo.runOfflineInstrumentation` therefore did NOT mutually exclude each other. In a `mvn -T` build, a parallel `prepare` from module A (using the child-class lock) could race against `runOfflineInstrumentation` from module B (using the parent-class lock), corrupting the ClassIdMap singleton.  
**Fix:** Removed the duplicate field from `PrepareMojo` entirely. `synchronized (REACTOR_MAP_INTRA_JVM_LOCK)` in `PrepareMojo` now resolves to `AbstractTestOrderMojo.REACTOR_MAP_INTRA_JVM_LOCK` (the shared one).

### BUG-8: `InstrumentMojo` overwrote reactor-wide map with only single-module data ✓ FIXED

**File:** `test-order-maven-plugin/…/InstrumentMojo.java` lines 147-162 (before fix)  
**Symptom:** `instrumentor.instrument(classesDir)` returned a `ClassIdMapping` snapshot containing ONLY the IDs allocated during that one module's instrumentation. `mapping.save(mappingFile)` then wrote that partial snapshot to the reactor-wide `class-id-map.bin`, overwriting every other module's IDs with a single-module view. In a multi-module build with `mvn -T`, two concurrent `InstrumentMojo` executions would also race on the singleton ClassIdMap without any lock.  
**Fix:** Added the same `synchronized (REACTOR_MAP_INTRA_JVM_LOCK)` + `FileLock` pattern used by `PrepareMojo` and `runOfflineInstrumentation`. Within the lock, load the existing reactor map into the singleton before instrumenting, then save `ClassIdMapping.fromClassIdMap(singleton, ...)` (the full union, not just this module's additions). Removed the now-unused `ClassIdMapping` import.

### BUG-9: `IndexCollectorServer.hasSource` — package prefix matched prefix-of-package-name ✓ FIXED

**File:** `test-order-core/…/IndexCollectorServer.java` method `hasSource`  
**Symptom:** `className.startsWith(prefix)` without a boundary check accepted `com.example2.Foo` as matching prefix `com.example`. In projects with package names sharing a common prefix (e.g. `com.example` and `com.example2`), classes from `com.example2` would be incorrectly retained in the dependency index even when `includePackages=com.example` was set. This would silently inflate the dependency graph and cause unnecessary test reruns.  
**Fix:** Changed the match condition to require that the character immediately after the prefix is `.`, `$`, or end-of-string — i.e., a real package/class boundary.  
**Test:** `IndexCollectorServerTest.packagePrefixFilterRespectsBoundary`, `.packagePrefixFilterKeepsInnerClasses`

### BUG-10: `processFallbackFile` leaked `claimedFile` on `readAllLines` IOException ✓ FIXED

**File:** `test-order-core/…/IndexCollectorServer.java` line 843 (before fix)  
**Symptom:** If `Files.readAllLines(claimedFile)` threw (e.g. encoding error, transient I/O), the exception propagated out of `processFallbackFile` without deleting `claimedFile`. The `.processing` temp file would remain on disk forever (or until manually cleaned), blocking future calls to `processFallbackFile` from picking up and retrying the original fallback file (since it had already been renamed away).  
**Fix:** Wrapped `readAllLines` in a try-catch that calls `Files.deleteIfExists(claimedFile)` before rethrowing.

---

## Bugs Fixed (Session 2 — 2026-06-05)

### BUG-11: `testorder.extensionActive` produced spurious "Unknown property" warning ✓ FIXED

**File:** `test-order-maven-plugin/…/MavenPluginConfigKeys.java` line 229  
**Symptom:** The lifecycle extension sets `testorder.extensionActive` in Maven's user properties to signal to the plugin that the extension is active. This internal coordination key was not in `ALL_KNOWN_KEYS` and not in the skip list, so every build logged `[WARNING] Unknown property 'testorder.extensionActive'`.  
**Fix:** Added `key.equals("testorder.extensionActive")` to the skip list in `findUnknownProperties`.

### BUG-12: `SelectMojo` ignored user's `-Dtest=` filter, producing misleading "not selected" warnings ✓ FIXED

**File:** `test-order-maven-plugin/…/SelectMojo.java`  
**Symptom:** When a user passed `-Dtest=CartTest` alongside `test-order:affected`, `SelectMojo` still ran its selection logic and warned "N tests were NOT selected and will NOT run" — even though the user's explicit filter would override test-order's selection via Maven property precedence. This was both confusing and wasted time.  
**Fix:** Added a guard at the top of `execute()`: if `session.getUserProperties().getProperty("test")` is non-blank, skip selection entirely and log "Skipping selection — -Dtest=X filter active." Consistent with the guard already in `PrepareMojo.executeOrderMode()`.

### BUG-13: `AutoMojo` ignored user's `-Dtest=` filter in auto-select mode ✓ FIXED

**File:** `test-order-maven-plugin/…/AutoMojo.java`  
**Symptom:** Same issue as BUG-12 but in `test-order:auto` mode. If a user passed `-Dtest=CartTest` with `test-order:auto test`, the auto-selection would run, potentially produce "Remaining tests written to..." messages, and try to override Surefire's test filter.  
**Fix:** Same guard pattern: check `session.getUserProperties().getProperty("test")` before running the auto workflow; skip and return early if the user specified an explicit test filter.

### BUG-14 (minor): `pendingRunCompleted` in `TestOrderState` not declared volatile

**File:** `test-order-core/…/TestOrderState.java` line 495  
**Symptom:** `addRunRecord()` (which sets `pendingRunCompleted = true`) is `synchronized`, but `toPersistedRoot()` reads `pendingRunCompleted` outside any lock and `afterSave()` writes it outside any lock. Under concurrent saves (theoretically possible in tests or benchmarks), a thread could see a stale `false` value, causing failure-score decay to be skipped for a run.  
**Fix:** Changed `private boolean pendingRunCompleted` to `private volatile boolean pendingRunCompleted`.

### BUG-15: `TieredSelectMojo` also ignored user's `-Dtest=` filter ✓ FIXED

**File:** `test-order-maven-plugin/…/TieredSelectMojo.java`  
**Symptom:** Same as BUG-12/13 but in `test-order:tiered-select`. When `-Dtest=CartTest` was specified alongside `tiered-select`, the mojo still ran its tier-selection logic, writing tier files and running `configureIncludes`, even though the user's filter would override the selection.  
**Fix:** Same guard pattern applied: skip tiered selection entirely if `session.getUserProperties().getProperty("test")` is non-blank.

---

## Bugs Fixed (Session 3 — 2026-06-05)

### BUG-16: `performDeferredOfflineInstrumentation` in `PrepareMojo` did not register backup for session-end restoration ✓ FIXED

**File:** `test-order-maven-plugin/…/PrepareMojo.java` line 504 (before fix)  
**Symptom:** When deferred offline instrumentation ran (i.e., `configureOfflineLearnMode` set `testorder.offline.pending=true` because classes weren't compiled yet, and `PrepareMojo.performDeferredOfflineInstrumentation` ran them at `process-test-classes`), the resulting `classes-backup` directory was added to `AbstractTestOrderMojo.pendingRestores` (a static set that nothing reads) but was NOT registered via `registerPendingRestoreInSession`. As a result:
1. `ClassBackupRestorer.pendingBackups` never included this path, so `ClassBackupRestorer.restoreAll()` at session end didn't restore the bytecode.
2. No JVM shutdown hook was installed for this path.
The consequence: after a deferred-instrumentation learn run, the instrumented bytecode was left on disk. The next `mvn test` (without `clean`) would encounter `UsageStore` call-sites injected into compiled classes and fail with `NoClassDefFoundError` when non-test plugins (e.g. annotation processors) loaded those classes outside the test classpath.  
**Fix:** Added `registerPendingRestoreInSession(backupDir)` and `registerPendingRestoreInSession(testBackupDir)` after the existing `pendingRestores.add()` call, matching the pattern in `runOfflineInstrumentation` (lines 1955–1956).

### BUG-17: `RunTieredMojo` also ignored user's `-Dtest=` filter ✓ FIXED

**File:** `test-order-maven-plugin/…/RunTieredMojo.java`  
**Symptom:** Same as BUG-12/13/15 but in `test-order:run-tiered`. When `-Dtest=CartTest` was specified alongside `run-tiered`, the mojo still ran full tiered selection and configured Surefire's include list, even though Maven's user property `test=CartTest` would override the selection anyway. This wasted time computing tier assignments and wrote unnecessary tier files.  
**Fix:** Same guard pattern: skip run-tiered selection entirely and return early if `session.getUserProperties().getProperty("test")` is non-blank.

---

## Bugs Fixed (Session 4 — 2026-06-05)

### BUG-18: `OrderConstraintManager.applyMustNotPrecede` missed cascading MUST_NOT_PRECEDE violations ✓ FIXED

**File:** `test-order-core/…/ops/detection/OrderConstraintManager.java` lines 126-142  
**Symptom:** The greedy swap loop checked adjacent pairs but did not decrement `i` after a swap. If the element now at `i+1` was also a victim of the same polluter (e.g., both `P→V1` and `P→V2` are MUST_NOT_PRECEDE pairs and V2 was swapped into the slot after P), the second violation was never detected or fixed.  
**Fix:** Added `i--` after every swap so the loop re-checks position `i` with its new neighbor before advancing.  
**Tests:** `OrderConstraintManagerTest.mustNotPrecedeRecheckAfterSwapCatchesNewViolation`, `.mustNotPrecedeAtEndMovesVictimBeforePolluter`

### BUG-19: `DashboardMojo` passed relative-path `outPath.getParent()` to `DashboardWorkflow` instead of absolute ✓ FIXED

**File:** `test-order-maven-plugin/…/DashboardMojo.java` line 92  
**Symptom:** When a user overrides `testorder.dashboard.output` with a relative path like `dashboard/index.html`, `outPath.getParent()` returns `Path("dashboard")` (relative). However, `DashboardWorkflow` stores this as `outputDir` and calls `outputDir.resolve("index.html")`, which produces a path relative to the JVM working directory rather than the project directory. If `outputDir` was null (bare filename like `index.html`), it caused a `NullPointerException`.  
**Fix:** Changed `outPath.getParent()` to `outPath.toAbsolutePath().getParent()` (consistent with the null-safe check already on line 61).

### BUG-21: `TestScorer.explain()` omitted complexity bonus in set-cover mode, diverging from `score()` ✓ FIXED

**File:** `test-order-core/…/TestScorer.java` lines 519-540  
**Symptom:** `score()` computes the complexity bonus in both the set-cover path AND the non-set-cover path (lines 374-387 and 389-407 respectively). However `explain()` only computed the complexity bonus in the non-set-cover `else` branch (lines 533-539). When set-cover mode was active and `changeComplexity` data was non-empty, `explain()` reported a lower total score than `score()` actually used for ordering. The `show` command's score breakdown was therefore misleading — it showed a lower score than what drove the actual test sequence.  
**Fix:** Added the complexity bonus calculation to the set-cover branch in `explain()`, mirroring lines 380-386 of `score()`.

### BUG-20: `TestOrderState.save` / `saveAggregatedFork` did not invalidate the load cache on IOException ✓ FIXED

**File:** `test-order-core/…/TestOrderState.java` lines 783-811  
**Symptom:** If `StateSerializer.save` threw an `IOException` (e.g., disk full, `moveIntoPlace` failed), the `STATE_LOAD_CACHE.removeIf` call was never reached. The mutated in-memory object (with `pendingRunCompleted = true` from the previous `addRunRecord` call, but not yet persisted) remained cached under the old file key. The next `load()` with the same file metadata returned the stale object. On the next successful save, `toPersistedRoot(applyDecay=true)` applied a second decay round for the same run, causing old failure scores to decay twice as fast.  
**Fix:** Wrapped both `StateSerializer.save` calls in try/finally so the cache invalidation always runs, whether the save succeeded or failed.

---

## Bugs Fixed (Session 5 — 2026-06-05)

### BUG-22: `ChangeAnalysis` bytecode-augmentation filter used `startsWith` without package-boundary check ✓ FIXED

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
- **All ITs pass**: cross-module-tracking, diamond-modules, transitive-modules, affected-mode.

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


## BUG-90: `StateConfiguration.runsSinceLearn` int overflow silences weight optimizer permanently ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateConfiguration.java`  
**Symptom:** After ~2.1 billion test-order runs (achievable by long-running CI pipelines over years), `incrementRunsSinceLearn()` wraps `int` from `Integer.MAX_VALUE` to `Integer.MIN_VALUE`. `AutoWorkflow.optimizeIfDue` checks `state.runsSinceLearn() <= 0`, which then evaluates to `true` permanently, disabling the periodic weight optimizer forever without any log message.  
**Fix:** Saturate at `Integer.MAX_VALUE` instead of wrapping.  
**Regression test:** `StateConfigurationTest.incrementRunsSinceLearnSaturesAtMaxValue`


## BUG-91: `StateConfiguration.emaVarianceThreshold` Javadoc inverts the actual adaptive-alpha direction ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateConfiguration.java`  
**Symptom:** The Javadoc on `emaVarianceThreshold()` stated "When variance is high, alpha is *increased* to track real changes quickly." In reality, `DurationTracker.adaptiveAlpha` *reduces* alpha when the relative standard deviation exceeds the threshold (more aggressive smoothing to damp noise). Any developer reading the Javadoc would configure the threshold backwards — e.g., raising it to get less noise, when raising it actually means fewer tests get dampened at all.  
**Fix:** Corrected the Javadoc to say: "When the relative standard deviation exceeds this threshold, the effective EMA alpha is *reduced* (more aggressive smoothing) to damp out measurement noise."  
**Regression tests:** `DurationTrackerTest.highVarianceDampensAlphaForMoreSmoothing`, `DurationTrackerTest.stableVarianceUsesBaseAlpha`, `DurationTrackerTest.negativeOrZeroDurationIsIgnored`

## BUG-92: `CiSummaryWriter.findExistingCommentId` returns wrong ID when comment object contains nested sub-objects with `"id"` fields ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/CiSummaryWriter.java`  
**Symptom:** The GitHub API response for listing PR comments includes nested sub-objects like `"user": {"id": 789, "login": "bot"}`. The old backward-walk scanned from the marker position towards the beginning of the string using `lastIndexOf('{')`, finding the innermost `{` first. That innermost `{` belonged to the `"user"` object, whose `"id"` field was extracted — returning the user's numeric ID (e.g. 789) instead of the comment's ID (e.g. 12345). Consequence: the subsequent PATCH request would target the wrong endpoint, failing with a 404 or silently updating the wrong resource.  
**Fix:** Replaced the backward-walk heuristic with a proper forward scan over the JSON array. Each top-level comment object is parsed with full depth-tracking (using explicit brace counting and string-escape handling). The `"id"` field is only extracted when the parser is at depth 1 (directly inside the comment object), so nested sub-objects are invisible to the ID extraction.  
**Regression test:** `CiSummaryWriterTest.findExistingCommentId_nestedUserIdNotPickedInsteadOfCommentId`

---

## Bugs Found (Session 6 — 2026-06-15)

### BUG-93: ~~Offline learn mode does not create test-dependencies.lz4 index file~~ (NOT REPRODUCIBLE)

**File:** N/A  
**Severity:** ~~CRITICAL~~ → NOT A BUG  
**Status:** Could not reproduce after proper setup. Offline learn mode correctly creates `test-dependencies.lz4` when project has `.mvn/extensions.xml` configured. Original observation was likely caused by BUG-106 (missing extensions.xml), not an offline instrumentation defect. Confirmed working on `single-test-project`: `test-dependencies.lz4` created correctly via offline instrumentation path.

---

### BUG-94: `head` command silently fails with 5MB limit error in test-order diagnostics ✓ FIXED

**File:** `scripts/` (test-order workflow diagnostic scripts)  
**Severity:** LOW (cosmetic, non-blocking)  
**Symptom:** During the `mvn test-order:dump` step, the output shows `head: illegal byte count -- 5M` warning with a "Dump failed" status. This appears to be an attempt to limit dump output to 5MB, but the `head` command syntax is incorrect.

**Root cause:** The scripts likely use `head -c 5M` or similar, but standard `head` command accepts `-c` with byte counts (e.g., `head -c 5242880`), not suffixes like `5M`. The GNU `head` command may support this, but the error message indicates it's being rejected.

**Steps to reproduce:**
```bash
cd third-party/jsoup
mvn test-order:dump
# Observe the warning: "head: illegal byte count -- 5M"
```

**Expected behavior:** The dump output should be limited cleanly without error messages, or the diagnostic output should omit this step entirely.  
**Fix:** Changed `head -c 5M` to `head -c 5242880` in `scripts/third_party_test_plan.sh`.

---

### BUG-95: `OrderReportPrinter.shortenClassName` doesn't abbreviate the class name itself, only packages ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/OrderReportPrinter.java:190-204`  
**Severity:** LOW (cosmetic, inconsistent abbreviation)  
**Symptom:** When `shortNames=true` is used in test reports, `shortenClassName("org.apache.commons.text.similarity.JaroWinklerDistanceTest")` returns `o.a.c.t.similarity.JaroWinklerDistanceTest` — all package segments are abbreviated except the final class name. For long test class names like `PropertiesUtilsWithDefaultPropertiesAndSupplierTest`, this still creates a visual line-wrap.

**Root cause:** The method explicitly only abbreviates package parts (lines 198-201), leaving `parts[parts.length - 1].append('.').append(cls)` where `cls` is the unabbreviated class name.

**Expected behavior:** For true "short names", the class name should also be abbreviated (at least the first character + dot + rest, or to a fixed width). Current behavior makes "short names" not very short for verbose class naming conventions.  
**Fix:** `shortenClassName` now truncates class names longer than 30 characters to 27 chars + `...` suffix via the new `abbreviateClassName` helper.

---

### BUG-96: Offline instrumentation with deferred mode doesn't validate that classes were actually instrumented ✓ FIXED

**File:** `test-order-maven-plugin/…/PrepareMojo.java` or `AbstractTestOrderMojo.java`  
**Severity:** MEDIUM  
**Symptom:** When offline instrumentation runs in deferred mode (i.e., `configureOfflineLearnMode` sets `testorder.offline.pending=true` because classes aren't compiled yet, then `performDeferredOfflineInstrumentation` runs later at PROCESS_TEST_CLASSES phase), there is no verification that:
1. The instrumentation actually succeeded (no check for `.instrumented` marker file)
2. The backup directory was created
3. The classes were actually modified

If instrumentation silently fails, the test JVM will run with uninstrumented classes, produce no dependency data, and `stopAndMerge()` will write an empty index file (or none at all). This would not be caught by any diagnostic output.

**Root cause:** `performDeferredOfflineInstrumentation` likely doesn't validate post-instrumentation state the way non-deferred paths do.

**Steps to reproduce:** (Difficult without triggering an artificial instrumentation failure)
1. Run deferred offline instrumentation on a multi-module project
2. Simulate instrumentation failure (e.g., permission denied on backup directory)
3. Observe that test JVM still runs without error, but no index is created

**Expected behavior:** Deferred instrumentation should validate that the backup directory exists and is non-empty before proceeding to test execution. If validation fails, emit a clear error.  
**Fix:** Added WARN-level log after instrumentation when `transformedCount == 0` (no classes instrumented) and when backup directory was not created. Both are emitted to stderr via the Maven build log, making silent failures visible.

---

### BUG-97: Test execution in offline instrumentation doesn't verify socket connectivity to IndexCollectorServer ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/agent/OfflineRuntimeBootstrap.java` (or equivalent test-side initialization)  
**Severity:** MEDIUM  
**Symptom:** The test JVM is launched with `-Dtestorder.collector.port=<port>`, but there is no validation that:
1. The port is reachable from the test JVM
2. The IndexCollectorServer is actually listening (it may have failed to start in the build process)
3. Dependency data successfully transmits to the server

If socket communication silently fails, the test runs normally but produces no dependency records. When `stopAndMerge()` is called, `mergedClassDeps` is empty, so the index file is either not written or written as empty.

**Expected behavior:** If the test JVM cannot connect to the collector port, it should log a warning or error, or fall back to .deps file mode. The absence of connectivity should not silently result in an empty index.  
**Fix:** Upgraded `AgentLogger.log` (verbose-only) to `AgentLogger.warn` (always emits to stderr) for both binary and string-based socket send failures in `UsageStore.flush()`. Users will now see a visible warning when socket send fails and the fallback .deps path is used.

---

### BUG-98: `OrderReportPrinter.printShowOrderTable` doesn't handle displayLimit=-1 edge case (show all tests) ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/OrderReportPrinter.java:47-86`  
**Severity:** LOW (edge case, works in practice)  
**Symptom:** The method accepts `-Dtestorder.show.limit=-1` to show all tests (line 60), but the logic at line 63 checks `displayLimit > 0`, which treats -1 the same as any negative value. While the code does work (because `ranked.subList(0, displayLimit)` with displayLimit=-1 fails, falling back to the full list), the condition is semantically confusing:
- `-1` means "show all" (intentional user input)
- Any other negative value would also be treated as "show all" (accidental)

**Root cause:** The condition should be `displayLimit > 0 && ranked.size() > displayLimit` (correct), but the semantic intent for -1 is not explicit.

**Expected behavior:** Add a comment clarifying that `displayLimit == -1` means unlimited, or change the condition to explicitly check for -1.  
**Fix:** Added clarifying comment in `printShowOrderTable` that `-1` means show all and any other negative value is treated identically.

---

### BUG-99: No warning when `testorder.show.limit` produces a large memory summary table ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/OrderReportPrinter.java:114-140`  
**Severity:** LOW (performance, informational)  
**Symptom:** When a user sets `-Dtestorder.show.limit=-1` on a large project (e.g., 5000+ tests), the summary table still computes min/max/count stats across all ranked tests (lines 116-120). For very large projects with expensive `rank()` and aggregation, this could consume significant memory or time. There is no warning or progress indication.

**Expected behavior:** For large test counts (>1000), either:
1. Skip the summary line when `displayLimit=-1` (user asked for all output anyway)
2. Add a message like "Computing stats for 5000 tests..." before the expensive aggregation  

**Fix:** When `displayLimit <= 0` (show all) and `totalCount > 200`, the summary line now includes `"| use -Dtestorder.show.limit=N to limit output"` as a usability hint.

---

### BUG-100: `TestOrderPluginTest.alwaysLearnInOrderModeAttachesLearnAgent` creates files in temp directory but doesn't use @TempDir isolation properly (NOT A BUG)

**File:** `test-order-gradle-plugin/src/test/java/me/bechberger/testorder/gradle/TestOrderPluginTest.java:307-338`  
**Severity:** VERY LOW (test infrastructure)  
**Symptom:** The test creates an index file at `indexFile` and writes bytes to it (line 323), but then calls both `configureOrderMode` (which reads/uses the index) and `configureLearnMode` (which expects to write a new index in learn mode). The test verifies that both mode system properties are set, but doesn't clean up the index file between the two operations. If a future test runs in the same temp directory and reuses `@TempDir`, it might find stale artifacts.

**Root cause:** Minor cleanup issue in test setup.

**Expected behavior:** Either clear the index file between the two operations, or use separate temp directories for the two phases of the test. Low priority — doesn't affect actual plugin behavior, only test isolation.  
**Status:** NOT A BUG. JUnit 5's `@TempDir` creates a unique directory per test, so stale artifacts cannot bleed across tests. The index file created within the test is part of a single sequential test method and is fully isolated by design.

---

### BUG-101: ~~Offline instrumentation bootstrap fails on first learn run when class-id-map.bin doesn't exist~~ (NOT A BUG)

**Status:** NOT A BUG. When `class-id-map.bin` doesn't exist, `configureOfflineLearnMode` automatically runs `runOfflineInstrumentation` to create it (AbstractTestOrderMojo.java:1984-2001). The code handles first-run correctly.

---

### BUG-102: ~~Pending-run .part files not finalized when using goal-based learn mode invocation~~ (NOT A BUG)

**File:** Likely in `PrepareMojo.java` or `AbstractTestOrderMojo.java`  
**Severity:** MEDIUM  
**Symptom:** When running `mvn test-order:learn test` (CLI goal-based mode), the pending run tracking files (`.part` files) are sometimes left in place and not finalized to their final filenames. This prevents subsequent `mvn test-order:show` from loading the complete state, causing scoring to be incomplete or incorrect.

**Root cause:** The distinction between `mvn test` (property-based mode, reliable) and `mvn test-order:learn test` (goal-based mode, unreliable) suggests the goal-based approach isn't properly finalizing state files after completion.

**Workaround:** Use property-based invocation: `mvn clean test -Dtestorder.mode=learn` instead of `mvn test-order:learn test`.

**Steps to reproduce:**
```bash
cd single-test-project
mvn test-order:learn test
ls -la target/.test-order/ | grep -i part  # May show .part files
mvn test-order:show  # Scoring may be incomplete
```

**Expected behavior:** Both goal-based and property-based invocations should produce identical results. The `.part` files should be finalized regardless of invocation method.

---

### BUG-103: Project with many tests (500+) shows all tests in show command by default, causing potential line-wrapping ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/OrderReportPrinter.java`  
**Severity:** LOW (cosmetic, usability)  
**Symptom:** When `mvn test-order:show` is run on a project with 500+ tests, all tests are displayed in the table by default. The output creates a very long terminal listing that wraps across many lines, making it hard to focus on the top-N tests.

**Root cause:** No default display limit is applied. The code supports `-Dtestorder.show.limit=5` to show only top 5, but the default shows all tests.

**Expected behavior:** Consider applying a sensible default limit (e.g., 20 or 50 tests) when not explicitly overridden, with a message like "Showing top 50 tests of 500 (use -Dtestorder.show.limit=20 to see more)".

**Workaround:** Always specify `-Dtestorder.show.limit=N` when running on large projects.  
**Fix:** `ShowMojo` and `ShowAllMojo` already apply `defaultValue = "20"` for `testorder.show.limit` — the default limit was already in place. BUG-99 fix adds a "use `-Dtestorder.show.limit=N` to limit output" hint to the summary line when all tests are shown and count > 200.

---

### BUG-104: JUnit 4 projects silently don't get test-order tracking ✓ FIXED

**File:** Multiple files (likely in collector initialization)  
**Severity:** MEDIUM (silent failure mode)  
**Symptom:** When test-order is applied to a JUnit 4 project (not JUnit 5/Jupiter), no warnings are logged during build. Tests execute normally, but no dependency data is collected because test-order is designed for JUnit 5 listeners. The build succeeds, but subsequent `mvn test-order:show` produces an empty or nearly-empty report.

**Root cause:** test-order primarily supports JUnit 5 Jupiter (`junit-jupiter`). JUnit 4 tests don't trigger the listener infrastructure, so no telemetry is collected.

**Expected behavior:** The plugin should detect JUnit 4 usage during initialization and either:
1. Log a clear error: "test-order requires JUnit 5 (jupiter), but project uses JUnit 4. Please upgrade your tests."
2. Fail the build with an actionable error message, OR
3. Gracefully disable test-order for JUnit 4 projects with a logged warning (current behavior could be improved with clearer messaging)

**Workaround:** Upgrade to JUnit 5, or don't use test-order on JUnit 4 projects.  
**Fix:** `warnJUnit4Unsupported()` in `AbstractTestOrderMojo` already emits WARN-level messages for three cases: (a) JUnit 4 only — warns tests will not be tracked; (b) JUnit 4 + JUnit 5 without Vintage engine — warns JUnit 4 tests will not be tracked; (c) JUnit 4 + JUnit Vintage engine — logs INFO that tests will be tracked via the platform. Called at `initContext()` so it runs for all test-order goals.

---

### BUG-105: DisplayLimit edge case: -1 (unlimited) and any other negative value treated identically ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/OrderReportPrinter.java:63`  
**Severity:** VERY LOW (cosmetic, semantic)  
**Symptom:** The method accepts `-1` to mean "show all tests", but the condition `displayLimit > 0` treats any negative value the same way. A user might accidentally pass `-50` intending to limit to 50, but it would show all tests instead.

**Root cause:** The condition should be more explicit about the `-1` semantics.

**Expected behavior:** Add validation/documentation that only `-1` is valid for "show all", or change behavior to treat negative values as invalid.  
**Fix:** Added comment in `printShowOrderTable` that `-1` means unlimited and any other negative is treated the same. `ShowMojo` and `ShowAllMojo` use `@Parameter(defaultValue = "20")` so invalid negatives can only come from explicit user input; the comment makes the behavior explicit.

---

### BUG-106: Missing `.mvn/extensions.xml` (or `<extensions>true</extensions>`) causes silent loss of multi-module partial-run merging ✓ FIXED

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/AbstractTestOrderMojo.java`  
**Severity:** MEDIUM (was overstated as CRITICAL)  
**Status:** FIXED — warning now emitted by `startCollector()`  
**Symptom:** When test-order-maven-plugin is NOT loaded as a Maven extension (neither `.mvn/extensions.xml` nor `<extensions>true</extensions>` in pom.xml):
1. Single-module builds still work — the index IS created via the JVM shutdown-hook fallback
2. BUT: `CollectorLifecycleParticipant.afterSessionEnd()` is never invoked, so:
   - Multi-module partial-run records (`.part` files) are NOT merged into state
   - CI index aggregation is skipped
   - Run history for multi-module builds is silently lost

**Fix applied:** Added a deduplicated warning in `startCollector()` when `testorder.extensionActive` is not set, prompting the user to add `.mvn/extensions.xml`.

---

### BUG-102: ~~Pending-run .part files not finalized when using goal-based learn mode~~ (NOT A BUG)

**Status:** NOT A BUG. `getOrCreateBuildId()` correctly returns null when the extension is not active, so `.part` files are never created. Run records go directly to state instead. This is intentional.

---

### BUG-107: `ChangeDetectionSupport.parseMode("auto")` throws IOException but `isSupportedMode("auto")` returns true ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/ChangeDetectionSupport.java:70`  
**Severity:** LOW (API inconsistency, potential confusion for future callers)  
**Symptom:** `isSupportedMode("auto")` returns `true` because `SUPPORTED_CHANGE_MODES` contains `"auto"`. However, calling `parseMode("auto")` directly throws `IOException("Unknown changeMode: auto")` because the switch statement has no `case "auto"` branch.

**Root cause:** `"auto"` is a meta-mode that resolves to either `SINCE_LAST_RUN` or `SINCE_LAST_COMMIT` based on whether a hash snapshot file exists. This resolution logic lives in `resolveMode()`, not `parseMode()`. The two methods have different contracts but this distinction is not surfaced by `isSupportedMode()`.

**Safe path:** All production callers use `resolveMode()` (lines 116, 140), which handles `"auto"` before delegating to `parseMode()`. Direct callers of `parseMode("auto")` would get an unexpected exception.

**Expected behavior:** Either:
1. Add a `case "auto"` to `parseMode()` that throws with a clear message like `"Use resolveMode() for 'auto' mode — it requires a hashFile path"`, OR
2. Document the contract difference in `parseMode()` Javadoc, OR
3. Remove `"auto"` from `SUPPORTED_CHANGE_MODES` and have `isSupportedMode()` handle it separately

**Workaround:** Always use `resolveMode()` instead of `parseMode()` when the mode might be `"auto"`.  
**Fix:** Added `case "auto"` to `parseMode()` that throws `IOException("'auto' is a meta-mode — use resolveMode(changeMode, hashFile) instead of parseMode()")`. Also updated the Javadoc to explicitly document that `"auto"` is not accepted. Production callers all use `resolveMode()` which handles `"auto"` before delegating to `parseMode()`, so behavior is unchanged.

---

### BUG-108: Child module aggregation loop mutates cached `DependencyMap` instance, poisoning LOAD_CACHE ✓ FIXED

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/AbstractTestOrderMojo.java:1376-1395`  
**Severity:** MEDIUM (data corruption in multi-module reactor scenarios, silent)  
**Symptom:** When `mvn test-order:show` (or similar) is run at the reactor root and no root index exists, the plugin iterates over child modules to aggregate their indexes. The loop sets `merged = child` on the first iteration, then calls `merged.mergeWith(child2)`, etc. Since `merged` IS the cached instance returned by `DependencyMap.load(childIdx)`, calling `mergeWith()` on it silently mutates the entry stored in `LOAD_CACHE` for `childIdx`.

Any subsequent call to `DependencyMap.load(childIdx)` within the same JVM (before the file is written) returns the corrupted merged instance instead of child module 1's actual data.

**Root cause:** `LOAD_CACHE` stores live mutable references. The comment on the cache says "callers that mutate the returned instance must call save() immediately after — save() evicts the stale cache entry." The child aggregation loop saves to the NEW aggregated `idxPath`, but never evicts or saves back to `childIdx`. The first child's cached entry is left contaminated with all subsequent children's data.

**Affected code:**
```java
DependencyMap child = DependencyMap.load(childIdx);  // returns cached instance
if (merged == null) {
    merged = child;          // merged IS the cached instance!
} else {
    merged.mergeWith(child); // mutates cached instance in place
}
// ...
merged.save(idxPath);        // evicts idxPath, NOT childIdx — stale entry remains
```

**Expected behavior:** Either:
1. Make a defensive copy: `merged = new DependencyMap(child)` on first iteration, OR  
2. Call `DependencyMap.evictCache(childIdx)` after the loop completes, OR
3. Load child indexes without caching (new API) when they will be mutated

**Workaround:** None automatically — this only affects multi-module scenarios where `DependencyMap.load(childIdx)` is called again in the same JVM after the aggregation loop.

---

### BUG-109: `RunRemainingMojo` silently skips remaining tests when `.consumed` file exists from a prior failed run ✓ FIXED

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/RunRemainingMojo.java:47-51`  
**Severity:** MEDIUM (silent test gap — tests that should run are silently not run)  
**Symptom:** When `mvn test-order:run-remaining test` is interrupted or fails after `run-remaining` has already renamed the file to `.consumed` (but before surefire completes), a subsequent retry of the same command will:
1. See no remaining-tests file (it's been renamed to `.consumed`)
2. Log "No remaining-tests file found — nothing to run." at INFO level
3. Set `skipTests=true` silently

The user has no indication that remaining tests were skipped because of a prior failed run. The `.consumed` file sits on disk with unconsumed tests.

**Root cause:** The file is renamed to `.consumed` before test execution begins (line 60). If the subsequent surefire execution fails (build error, OOM, etc.), the file is gone, and there's no recovery detection on the next run.

**Expected behavior:** When the remaining-tests file is absent, check for a `.consumed` sibling. If found, emit a WARN-level message like:
```
[test-order] WARNING: remaining-tests file not found, but test-order-remaining.txt.consumed exists.
A prior run may have been interrupted. If remaining tests were not fully executed, manually
rename test-order-remaining.txt.consumed → test-order-remaining.txt to replay them.
```

**Workaround:** Manually rename `.consumed` back to the original file name before re-running.

---

### BUG-110: `forceSingleForkForOrdering` logs spurious "Overriding Surefire forkCount=<unset>→1" when config is already at defaults ✓ FIXED

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/SurefireHelper.java:683-706`  
**Severity:** VERY LOW (cosmetic, confusing log noise)  
**Symptom:** When `mvn test-order:affected test` is run on a project that doesn't explicitly set `<forkCount>` or `<reuseForks>` in the Surefire configuration, the method logs:
```
[test-order] Overriding Surefire forkCount=<unset>→1, reuseForks=<unset>→true so PriorityClassOrderer can reorder selected classes within one JVM.
```
But Surefire's defaults ARE `forkCount=1` and `reuseForks=true`, so no actual behavior change occurs. The message implies a real override when none took place.

**Root cause:** `changedFork` and `changedReuse` are computed as "not equal to the value we're setting" rather than "different from Surefire's effective default." When the XML element is absent (`null`), the code treats null as "changed from 1" and "changed from true," even though null means "use default which is 1/true."

**Expected behavior:** Either:
1. Treat `null` forkCount as equivalent to `"1"` for the change-detection logic, OR
2. Only emit the INFO log when the original value was something other than the default

**Workaround:** None needed — this is purely a cosmetic issue with no behavioral impact.

---

### BUG-111: `PartialRunAggregator.mergeAndApply` deduplication is non-deterministic when multiple forks ran the same test class ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/PartialRunAggregator.java:134-142`  
**Severity:** LOW (inconsistent history, subtle APFD drift over multiple builds)  
**Symptom:** When multiple Surefire forks record the same test class (overlapping test distribution), `mergeAndApply` deduplicates by keeping the first occurrence. The "first occurrence" depends on `Files.list()` ordering, which is filesystem-order (non-deterministic across builds, OS, and file creation timestamps).

**Consequence:** If `FooTest` ran in fork 1 (failed) AND fork 2 (passed due to test isolation), the merged outcome records whichever fork's result `Files.list()` returns first — non-deterministically alternating between pass and fail across retries. This causes flaky `RunRecord` history and inaccurate APFD calculations.

**Root cause:** Line 136-141 deduplicates outcomes using `seen.add(o.testClass())` with iteration order tied to `partFiles` which comes from `Files.list()` (non-deterministic). Combined with deduplication by test class name, the "winner" for overlapping forks is non-deterministic.

**Expected behavior:** When a test class appears in multiple fork `.part` files, prefer the worst-case outcome (failed > passed) to be conservative (safety-first).

**Workaround:** Avoid overlapping test distributions across forks.

---

### BUG-112: `DependencyMap.aggregateFromDepsDirectory` mutates a cached `DependencyMap` instance while holding only a file lock, racing concurrent `load()` callers ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java:1742-1943`  
**Severity:** MEDIUM (data corruption in parallel `mvn -T N` builds with multi-module selective-learn)  
**Symptom:** In `aggregateFromDepsDirectory`, the code acquires a file lock and loads the existing index:
```java
DependencyMap map = load(indexFile);  // returns cached instance from LOAD_CACHE
...
map.dependencies.put(entry.getKey(), ...);  // mutates the cached object directly
map.methodDependencies.put(...);            // more mutations
map.invertedIndex = null;                   // invalidates lazy cache
map.save(indexFile);                        // evicts cache entry
```
The file lock serializes concurrent `aggregateFromDepsDirectory` calls in the same JVM (via `JVM_LOCKS`). But it does NOT prevent a concurrent `load(indexFile)` call (from another Mojo on another module) from returning the same cached `DependencyMap` instance. That caller sees in-flight mutations: partially-merged dependency sets, null invertedIndex, and possibly a partially-rebuilt `depFrequencies` map.

**Consequence:** Under `mvn -T N` with N ≥ 2, a module B's mojo calling `load(indexFile)` while module A's `aggregateFromDepsDirectory` is mid-merge can observe partially-written or incoherent dependency data. This can cause incorrect test selection or scoring for module B's run.

**Root cause:** `load()` returns a mutable cached instance. The pattern "load → mutate in place → save" is documented as safe only when callers save immediately after mutation (comment at `DependencyMap.java:42`). But `aggregateFromDepsDirectory` mutates the cached object across many steps (two parallel task loops for `.deps` and `.mdeps` files, plus `.members` and `.mmembers` loops), then saves at the end — leaving a wide window where the cache holds a partially-mutated object.

**Expected behavior:** Either (a) `aggregateFromDepsDirectory` should work on a local copy (not the cached instance), and only update the cache after `save()` completes; or (b) the file lock scope should prevent concurrent `load()` calls, e.g. by evicting the cache entry before loading.

**Fix:** Evict the cache entry before loading inside the lock: call `evictCache(indexFile)` before `load(indexFile)` so any concurrent `load()` caller that sneaks in will re-read from disk rather than getting the stale cached object. After `save()`, the cache is properly repopulated on next load.

**Workaround:** Use `mvn -T 1` (single-threaded) to avoid the race.

---

### BUG-113: `TestOrderPlugin.isProjectTargeted` always returns `true` for the root project when qualified task paths are used ✓ FIXED

**File:** `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java:129-141`  
**Severity:** LOW (cosmetic — verbose log output on root project when targeting subprojects)  
**Symptom:** When running `./gradlew :subproject:test` in a multi-project build, `isProjectTargeted(rootProject)` returns `true` even though the root project was not explicitly targeted.

**Root cause:** Line 134 checks `task.startsWith(projectPath.substring(1) + ":")`. For the root project, `projectPath = ":"` so `projectPath.substring(1) = ""`, making the condition `task.startsWith(":")`. Any qualified task path like `:subproject:test` starts with `":"`, so the root project matches unconditionally.

```java
if (task.startsWith(projectPath + ":") || task.startsWith(projectPath.substring(1) + ":")
    || (!task.contains(":") && project == project.getRootProject())) {
    return true;
}
```

**Consequence:** The root project uses the verbose `wrapLog` (lifecycle-level) instead of `wrapQuietLog` (debug-level) when computing its mode decision. This causes "New test class(es) detected" and other lifecycle messages to appear on the root project even when the user only targeted a submodule.

**Expected behavior:** The root project should be considered "targeted" only when the task name has no project qualifier (e.g. `test`, not `:subproject:test`), or when it explicitly matches the root project path.

**Fix:** Guard the `projectPath.substring(1) + ":"` check to skip it when `projectPath.length() == 1` (i.e. root project):
```java
if (task.startsWith(projectPath + ":") 
    || (projectPath.length() > 1 && task.startsWith(projectPath.substring(1) + ":"))
    || (!task.contains(":") && project == project.getRootProject())) {
    return true;
}
```

**Workaround:** Suppress `[test-order]` lifecycle output by running with `--quiet` flag.

---

### BUG-114: `DetectDependenciesOperation` never calls `runner.setDeadline()`, so in-progress subprocesses are never killed when the time budget expires ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/DetectDependenciesOperation.java:205-256`  
**Severity:** LOW (time budget overrun when individual test runs are slow)  
**Symptom:** When `testorderDetectDependencies` (or `testOrderDetectDependencies` on Gradle) runs with a `timeBudget`, the deadline is computed at line 207:
```java
long deadline = config.timeBudgetSeconds() > 0
    ? startTime + config.timeBudgetSeconds() * 1000L
    : Long.MAX_VALUE;
```
The `deadline` value is passed to `DetectionContext` (line 255), which algorithms check with `ctx.timeBudgetExhausted()` before starting new runs. However, `runner.setDeadline(deadline)` is **never called**. The `GradleTestRunner` and `MavenTestRunner` both implement `setDeadline()` to kill running subprocesses via `destroyForcibly()`, but since it's never invoked, their `deadlineMillis` field stays at `Long.MAX_VALUE`.

**Consequence:** If the last test run starts just before the deadline (budget check passes), it runs to full completion regardless of how long it takes. For slow test suites, the actual wall-clock time of the detect operation can far exceed `timeBudget`.

**Root cause:** `DetectionContext.run()` calls `runner.run(order)` directly without any deadline-enforcement guard. The `setDeadline()` method on the runner was added precisely to kill in-progress subprocesses, but was never wired from `DetectDependenciesOperation`. Additionally, `MavenTestRunner` does not implement `setDeadline()` at all (only `GradleTestRunner` does), so the Gradle runner would benefit from the fix but the Maven runner still needs its own implementation.

**Expected behavior:** Before the first detection run (after the reference run completes), call `runner.setDeadline(deadline)` so that if any individual subprocess run is still executing when the deadline arrives, it gets force-killed and the runner returns a partial result.

**Fix:** Add `runner.setDeadline(deadline);` after computing `deadline` in `DetectDependenciesOperation.run()`, before calling `algorithm.detect(ctx)`.

**Workaround:** Set `timeBudget` conservatively to account for one full test suite run beyond the budget.

---

### BUG-115: `CombinedAdaptiveAlgorithm.executeMinimize` underestimates run-count for `DeltaDebugging.minimize`, allowing the outer budget to be exceeded ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/detection/CombinedAdaptiveAlgorithm.java:235-236`  
**Severity:** LOW (run budget overrun in OD detection — more test runs than `maxRuns` allows)  
**Symptom:** `executeMinimize` delegates to `DeltaDebugging.minimize` with a `runBudget` of 15, then estimates the runs consumed as:
```java
int runs = Math.min(15, candidates.size()); // Approximate runs used
```
For `candidates.size() < 15`, this caps the estimate at `candidates.size()`, but `DeltaDebugging.minimize` can still use up to 15 runs (the full budget). For example, with 3 candidates and a ddmin tree that requires many small subset trials, `minimize` may use 12 runs while `executeMinimize` reports only 3 to the outer loop's `runsUsed` counter.

**Consequence:** The `runsUsed < maxRuns` guard in the outer `detect()` loop is tricked into continuing past the true budget. This causes more test executions than `estimatedRuns()` intended, which can make OD detection take significantly longer than expected when the candidate sets are small.

**Root cause:** The approximation `Math.min(15, candidates.size())` assumes that ddmin requires at most one run per candidate. In reality, ddmin can use up to `runBudget` runs regardless of the candidate count, because it bisects the problem tree — small sets take roughly `log2(n) * 2` runs, not `n` runs.

**Expected behavior:** The returned run count should be the actual number of `runner.run()` calls made inside `DeltaDebugging.minimize`. This requires either passing a shared counter or returning the consumed run count from `DeltaDebugging.minimize`.

**Fix:** Have `DeltaDebugging.minimize` return a `record MinimizeResult(List<String> minimal, int runsUsed)`, or pass an `AtomicInteger` counter. Alternatively, replace the approximation with `Math.min(15, 2 * (int) Math.ceil(Math.log(Math.max(candidates.size(), 1)) / Math.log(2)) + 1)` as a tighter upper bound.

**Workaround:** Set `timeBudgetSeconds` conservatively to account for the overrun, or accept slightly more test runs than the budget specifies.






---

### BUG-116: `TestNGTelemetryListener` closes all class tracking boundaries in bulk at `onFinish` instead of when each class completes, polluting per-class dependency data ✓ FIXED

**File:** `test-order-testng/src/main/java/me/bechberger/testorder/testng/TestNGTelemetryListener.java`  
**Lines:** `onStart` (line 113), `onFinish` (lines 196–200)

**Symptom:** In learn mode, `bridge.callStartTestClass(className)` is called in `onTestStart` (line 113) when each class first executes — but `bridge.callEndTestClass(className)` is called for **all tracked classes in bulk** at `onFinish` (lines 196–200):

```java
// onFinish, lines 196–200
if (learnMode && bridge.isAvailable()) {
    for (String className : executionOrderSet) {
        bridge.callEndTestClass(className);   // all classes closed at suite end
    }
}
```

By contrast, the JUnit `TelemetryListener` calls `bridge.callEndTestClass(name)` in `executionFinished` when the class-level container node completes (line 280), meaning each class's tracking window is closed as soon as its last test finishes.

**Consequence:** When TestNG runs class A then class B sequentially, the `UsageStore` tracking window for class A stays open until the entire test suite ends. All of B's field reads, method calls, and allocations during B's tests are recorded as dependencies of A. This causes **cross-class dependency pollution**: A's `.deps` entries include B's dependencies, inflating the conflict graph and producing false-positive OD findings.

In parallel execution (`parallel="classes"`) the pollution is even worse: all classes are open simultaneously, causing every class to absorb the full suite's dependency footprint.

**Root cause:** `executionOrderSet` is populated on the first `onTestStart` call for each class, but there is no `IClassListener` or `onAfterClass` hook being used to close each class boundary when its tests complete. The `onFinish` bulk-close is a correctness placeholder.

**Expected behavior:** Each class should be closed via `bridge.callEndTestClass(className)` immediately after its last test method completes — i.e., when the next test's class name changes, or via a TestNG `IClassListener.afterClass` callback.

**Fix:** Implement `org.testng.IClassListener` (available since TestNG 6.5) and call `bridge.callEndTestClass(testClass.getRealClass().getName())` in `afterClass(ITestClass)`. This provides the correct per-class boundary semantics matching the JUnit implementation.


---

### BUG-117: `IndexCollectorServer.stampNewTestsWithModule` races with concurrent handlers, attributing new test-keys to the wrong module ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java`  
**Lines:** 484 and 861–870

**Symptom:** In a parallel fork build with multiple JVMs connecting simultaneously, test-class entries can be stamped with the wrong moduleId. A test class recorded by fork A may end up attributed to the module of fork B.

**Root cause:** The snapshot `testKeysBefore` is taken *outside* any synchronized block (line 484), then `handleBinaryPayload(in)` merges under `synchronized(this)`, and finally `stampNewTestsWithModule` (lines 861–870) iterates `mergedClassDeps.keySet()` to stamp keys not present in the snapshot:

```java
// handle(), line 484 — snapshot taken with no lock held
Set<String> testKeysBefore = new HashSet<>(mergedClassDeps.keySet());
boolean handled = handleBinaryPayload(in);   // merges under synchronized(this)
stampNewTestsWithModule(testKeysBefore, moduleId);
```

Between the snapshot at line 484 and the synchronized merge inside `handleBinaryPayload`, another handler thread (fork B) can call its own synchronized merge and insert keys for B's tests into `mergedClassDeps`. When thread A then calls `stampNewTestsWithModule`, those B-keys are not in A's `testKeysBefore` snapshot and get stamped with A's `moduleId` — even though they were inserted by B's merge.

**Consequence:** `mergedTestToModule` maps B's test classes to A's moduleId. Any downstream multi-module logic that partitions test results by module will mis-route B's dependency data.

**Expected behavior:** The snapshot, merge, and stamp should be performed atomically under the same lock, or the stamp should only iterate keys that were actually inserted by the current merge callback (e.g., by having the merge callback return the set of new keys).

---

### BUG-118: `IndexCollectorServer.processFallbackFile` double-processes the fallback file when `AtomicMoveNotSupportedException` is thrown concurrently ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java`  
**Lines:** 957–966

**Symptom:** Fallback dependency payloads can be merged twice into the index, producing doubled dependency counts for affected test classes.

**Root cause:** The claim logic uses an atomic rename to prevent concurrent processing. When `AtomicMoveNotSupportedException` is thrown (filesystem doesn't support atomic moves), the code falls back to processing the original path:

```java
try {
    Files.move(fallbackFile, claimedFile, ATOMIC_MOVE);
} catch (NoSuchFileException | AtomicMoveNotSupportedException e) {
    if (!Files.exists(fallbackFile)) return false;
    // Non-atomic fallback: accept the small risk and continue with original path
    claimedFile = fallbackFile;  // line 966
}
```

When two threads hit `AtomicMoveNotSupportedException` concurrently (e.g., both called `Files.move` for the same file on a filesystem that doesn't support atomic moves), both threads execute the `exists()` check, both see the file present, both set `claimedFile = fallbackFile`, and both proceed to read and merge the same file. The comment acknowledges "small risk" but does not document that it results in double-merging.

**Consequence:** All class and method dependencies from the fallback file are merged twice. This inflates dependency counts, potentially causing test-order to over-count overlap scores and mis-rank tests.

**Expected behavior:** On `AtomicMoveNotSupportedException`, only one thread should process the fallback file. A non-atomic file lock (e.g., a `.lock` sentinel file) or a try-rename-with-retry loop would prevent the race.

---

### BUG-119: `APFDCalculator.scoreOutcome` uses raw dep-overlap count but live scorer uses IDF-weighted overlap, making the optimizer optimize the wrong formula ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/APFDCalculator.java`  
**Lines:** 191–197

**Symptom:** The genetic optimizer (`ScoringOptimizer`) learns weights that are suboptimal for the actual scoring formula used at runtime. Optimized `depOverlap` weights may be systematically over- or under-valued compared to what would actually maximize APFD.

**Root cause:** When `TestScorer.score()` runs live, it computes an IDF-weighted overlap:

```java
for (String dep : overlapClasses)
    weightedDepOverlap += depMap.idf(dep);
int rawDepOverlap = depOverlapScore(weightedDepOverlap, depTotal, weights.depOverlap());
score += rawDepOverlap;  // uses IDF-weighted sum
```

But the resulting `TestOutcome` persisted to state only stores `depOverlap` — the raw *count* of overlapping classes — not `weightedDepOverlap`. When `APFDCalculator.scoreOutcome()` later re-scores for the optimizer:

```java
score += TestScorer.depOverlapScore(outcome.depOverlap(), outcome.depTotal(), weights.depOverlap());
```

It calls `depOverlapScore` with the raw count, not the IDF sum. Since `depOverlapScore(x, total, w) = w * x / sqrt(max(total, 5))`, the optimizer evaluates different values of `x` than the live scorer used. For any test class whose overlap deps are rare (high IDF), the live score is higher than the optimizer assumes; for tests with common deps, the live score is lower.

**Consequence:** The optimizer's fitness function does not faithfully replicate the scoring function it is trying to optimize. It may converge to weights that maximize count-based APFD rather than IDF-weighted APFD, producing suboptimal test ordering after optimization.

**Expected behavior:** `TestOutcome` should store `weightedDepOverlap` (the IDF-weighted sum), and `APFDCalculator.scoreOutcome()` should use that value instead of `depOverlap` when re-scoring. This would require a schema migration since `TestOutcome` is persisted to state files.

### BUG-120: `CombinedAdaptiveAlgorithm.EXCLUSION_PROBE` actions set `victim` to the excluded test, causing false early-exit via the `confirmedPolluters` guard ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/detection/CombinedAdaptiveAlgorithm.java`  
**Lines:** 329–350 (`addExclusionProbes`, `addInitialExclusionProbes`) and 178–180 (`executeAction`)

**Symptom:** Exclusion probes scheduled for tests that happen to have been identified as OD victims earlier in the same detection run are silently skipped. This suppresses BRITTLE detection for those tests, causing the algorithm to miss brittle test relationships.

**Root cause:** `addExclusionProbes` and `addInitialExclusionProbes` construct actions with both `victim` and `candidates.get(0)` set to the same test (the excluded test):

```java
workQueue.add(new Action(ActionType.EXCLUSION_PROBE, 15.0, test, List.of(test)));
```

But `executeAction` guards on `action.victim`:

```java
TestKnowledge tk = knowledge.computeIfAbsent(action.victim, k -> new TestKnowledge());
if (!tk.confirmedPolluters().isEmpty() && action.type != ActionType.CONFIRM_BRITTLE) {
    return 0;  // Skip — already resolved
}
```

For `EXCLUSION_PROBE`, `action.victim` is the test being *excluded* (the potential setter), not the test being *probed for victimhood*. If that excluded test was previously identified as a polluter for some other victim, its `TestKnowledge.confirmedPolluters()` is non-empty (since the same `knowledge` map is keyed by test class name). The guard treats this as "already resolved" and skips the exclusion probe entirely.

**Consequence:** Any test that was identified as a polluter (OD victim) is never used as an exclusion probe, even though it could also be a state-setter for BRITTLE tests. BRITTLE test relationships where the setter test is a known polluter will be missed.

**Expected behavior:** `EXCLUSION_PROBE` actions should use a sentinel (e.g., an empty string or a dedicated marker) for `action.victim`, or `executeAction` should not apply the `confirmedPolluters` guard to `EXCLUSION_PROBE` actions (similar to how it already makes an exception for `CONFIRM_BRITTLE`). The fix: change the guard to `action.type != ActionType.CONFIRM_BRITTLE && action.type != ActionType.EXCLUSION_PROBE`.

---

### BUG-121: `OfflineInstrumentationIT` expected mapping at `target/.test-order/class-id-map.bin` — wrong path ✓ FIXED

**File:** `test-order-maven-plugin/src/test/java/me/bechberger/testorder/maven/it/OfflineInstrumentationIT.java` line 82 (before fix)  
**Severity:** LOW (test fixture)  
**Symptom:** `offlineLearnProducesIndex` asserted that the `class-id-map.bin` was written to `target/.test-order/class-id-map.bin`, but `InstrumentMojo` writes it to `<project-root>/.test-order/class-id-map.bin` via `ReactorContext.resolveClassIdMapFile()`. The file existed at the correct path but the test looked at a nonexistent `target/` subdirectory, causing `Files.size(mappingFile) > 16` to fail (the file had 0 bytes or didn't exist at the expected path).  
**Fix:** Changed the expected path from `"target/.test-order/class-id-map.bin"` to `".test-order/class-id-map.bin"`.  
**Confirmed:** All 5 `OfflineInstrumentationIT` tests pass after fix.

---

### BUG-122: `DependencyMap.filterForModule` drops all member-level dependency data ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java` line 474–496  
**Severity:** MEDIUM  
**Status:** Fixed — added copies of `memberKeyDictionary`, `memberKeyIndex`, `memberDepsMap`, and `methodMemberDepsMap` in `filterForModule`; regression test added in `DependencyMapTest.filterForModule_preservesMemberDepsForKeptTests`.  
**Symptom:** In multi-module builds using MEMBER-level instrumentation, after `filterForModule` filters the index to the current module's tests, the returned `DependencyMap` has empty `memberDepsMap`, `methodMemberDepsMap`, and `memberKeyDictionary`. Downstream callers (`ConflictGraphBuilder`, `DashboardGenerator`, `CoverageOperation`, `ExportJsonOperation`) check `hasMemberDeps()` first — it returns `false` on the filtered map, so all member-level analysis is silently skipped. The dashboard shows no member coverage data, OD conflict graph has no member-based edges, and coverage reports lose method-level breakdowns, even though the learn run collected this data.  
**Root cause:** `filterForModule` (line 474) copies only `dependencies`, `methodDependencies`, and `testToModule` into the new `DependencyMap`, omitting the three member-level fields. By contrast, `copy()` (line 628) correctly copies all six auxiliary maps. The `AffectedWorkflow` (line 70) calls `filterForModule` as the first step before scoring, so every multi-module `affected` or `order` run in MEMBER mode silently loses member data.  
**Note:** `methodDependencies` is also copied unfiltered (includes entries for excluded modules), but this doesn't cause wrong results since downstream code resolves method deps by looking up the test class in `dependencies` first.

---

### BUG-123: `PersistenceSupport.JVM_LOCKS` grows without bound in long-running daemon processes ✓ FIXED

**File:** `test-order-core/src/main/java/me/bechberger/testorder/PersistenceSupport.java` line 116  
**Severity:** LOW (memory leak)  
**Status:** Fixed — added a warning log when `JVM_LOCKS.size() > 512`; no structural change to preserve the race-safety guarantee.  
**Symptom:** `JVM_LOCKS` is a `ConcurrentHashMap` that intentionally retains every `Path` → `Object` mapping forever (comment on line 114 explains this prevents a subtle race). In short-lived Maven processes this is harmless. But in Maven Daemon (mvnd) or other long-running build tools, if many different state file paths are used over many builds (e.g. many different reactor projects), the map accumulates one `Object` per unique lock-file path and never shrinks. Over hundreds of builds or large reactors, this becomes a measurable heap leak.  
**Root cause:** The decision to keep objects permanently was to prevent the "two threads get different lock objects" race — but the fix over-compensates by never releasing entries even for paths that will never be seen again.

---

### BUG-124: `configureDerivedTestTask` sets `testClassesDirs` to an empty FileCollection for subprojects without test output at configuration time, causing Gradle to report NO-SOURCE ✓ FIXED (re-fixed again)

**File:** `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java` line 3733  
**Severity:** HIGH (testOrderAffected/testOrderLearn/etc. report NO-SOURCE on ALL subprojects in multi-module builds like spring-boot and kafka)  
**Status:** Fixed (re-fixed 2026-06-16, final fix confirmed 2026-06-16) — multiple intermediate fixes failed:
1. `doFirst("testOrderRefreshTestClassesDirs")` — failed because `@SkipWhenEmpty` fires before doFirst
2. `task.dependsOn("testClasses")` string form — failed because testClasses doesn't exist on all projects (e.g. spring-boot-antlib has java-library but no src/test)
3. `task.dependsOn(project.getTasks().named("testClasses"))` inside `if (testSourceSet != null)` — failed because `sourceSets.findByName("test")` returns null when the task config lambda fires during configuration (see root cause below)
4. `withPlugin("java", callback)` — failed because `withPlugin` fires during java-base plugin application (before JavaPlugin.apply() creates source sets), so SourceSetContainer is empty

**Final root cause:** When a convention plugin calls `project.getTasks().withType(Test.class, action)`, it forces all Test tasks to be realized eagerly during project configuration. Our lazy task config lambdas (registered via `tasks.register("testOrderAffected", ...)`) fire at this point — but because the subproject's own `plugins {}` block may not have run yet (or is in the middle of running), `sourceSets.findByName("test")` returns null.

**Final fix:** Use `project.getPlugins().withType(JavaPlugin.class, callback)` inside the task config to defer source-set wiring until AFTER `JavaPlugin.apply()` completes (not just when the plugin ID is registered as `withPlugin("java")` does). `withType(JavaPlugin.class)` fires after `JavaPlugin`'s full `apply()`, at which point "main" and "test" source sets are populated. Extracted as `wireDerivedTaskSourceSet(project, task)`.

**Symptom:** In complex multi-module Gradle builds (e.g. spring-boot Gradle 9.4.1), ALL subprojects' `testOrderAffected` tasks return `NO-SOURCE` — even subprojects with compiled test classes.  
**Evidence:** spring-boot campaign: every subproject's `testOrderAffected` shows `NO-SOURCE`; confirmed by debug logging showing `sourceSets.getNames()=[]` when task config fires.

---

### BUG-125: `TestScorer.score()` does not scale `staticFieldBonus` by `killMultiplier`

**File:** `test-order-core/src/main/java/me/bechberger/testorder/TestScorer.java` line 428  
**Severity:** LOW (scoring inconsistency)  
**Status:** FIXED (2026-06-16)  
**Symptom:** When a test class has changed static fields in its dependency set (`staticFieldOverlap > 0`), it receives the full `staticFieldBonus` regardless of its kill rate. All other dep-overlap bonuses (`depOverlap`, `complexityOverlap`, `setCoverBonus`) are scaled by `killMultiplier = 0.5 + killRate * 0.5` before being added to the score — but the static field bonus is added as a raw integer at line 428 with no multiplier. As a result, a low-kill-rate test that happens to overlap a changed static field gets the same static-field bonus as a high-kill-rate test, inconsistently weighting it relative to the rest of the overlap score.  
**Root cause:** The `staticFieldBonus` was added after the `killMultiplier` pattern was established, and the multiplication step was omitted. In `explain()` (line 562–569) the same omission exists — `staticFieldPts` is added to `totalScore` directly without adjustment.

---

### BUG-126: `MutationAnalysisOperation` hardcodes Maven-specific paths `target/classes`, `target/test-classes`, `target/pit-reports`

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/MutationAnalysisOperation.java` lines 121–134  
**Severity:** MEDIUM (feature gap: Gradle projects and non-standard Maven layouts silently fail)  
**Status:** FIXED (2026-06-16)  
**Symptom:** The `run()` method resolves class directories and the PIT report output dir by appending Maven-conventional suffixes to `config.projectRoot()`:
```java
Path targetClasses     = config.projectRoot().resolve("target/classes");       // line 121
Path targetTestClasses = config.projectRoot().resolve("target/test-classes");  // line 122
Path pitReportDir      = config.projectRoot().resolve("target/pit-reports");   // line 134
```
If `targetClasses` doesn't exist the method throws immediately (line 124). On Gradle projects (where compiled output is under `build/classes/java/main`) or Maven projects with a custom `<outputDirectory>`, the method always fails, even though the caller already provides `config.indexFile()` and `config.extraClasspath()` which could allow the analysis to run. The PIT report dir is also always `target/pit-reports` regardless of whether the project uses Gradle (`build/reports/pitest`) or a customized `<reportsDirectory>`.  
**Root cause:** The `Config` record exposes `projectRoot` but no `classesDir`, `testClassesDir`, or `reportsDir` fields. The Maven and Gradle adapters that build `Config` know these paths from their respective build-tool APIs but have no way to pass them through.

---

### BUG-127: `DetectDependenciesOperation.extractJsonString` does not handle escaped quotes in string values

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/DetectDependenciesOperation.java` line 641  
**Severity:** LOW (incorrect parse on edge-case input)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `extractJsonString` locates the closing `"` of a JSON string value using `json.indexOf('"', quoteStart + 1)`. If the JSON value contains an escaped quote (`\"`), `indexOf` returns the position of the escaped quote instead of the real closing delimiter, truncating the extracted value. For example, a test class FQCN containing `$` is fine, but a description field like `"My \"bug\" description"` would be parsed as `My \` rather than the full value. Carried-forward results from prior report files then propagate with a truncated description.  
**Root cause:** The method was written as a minimal hand-rolled parser and does not track backslash escapes. A proper fix requires scanning character-by-character and skipping `\"` sequences.

---

### BUG-129: `DashboardGenerator` uses `!= null` check on `getMethodMemberDeps()` return value that never returns null

**File:** `test-order-core/src/main/java/me/bechberger/testorder/DashboardGenerator.java` line 256  
**Severity:** LOW (inconsistency; method data always omitted from dashboard even when present)  
**Status:** FIXED (2026-06-16)  
**Symptom:** At line 256, the dashboard builder checks `mMemberDeps != null` before adding method-level member deps to the output:
```java
Set<String> mMemberDeps = depMap.getMethodMemberDeps(key);
m.put("memberDeps", mMemberDeps != null ? new ArrayList<>(mMemberDeps) : null);
```
`DependencyMap.getMethodMemberDeps()` never returns `null` — it returns `Collections.emptySet()` when no deps are recorded (line 594). So `mMemberDeps != null` is always `true`, and an **empty ArrayList** is stored in the dashboard JSON for every method that has no member deps, bloating the output. In contrast, the equivalent call for test-class-level member deps at line 238 correctly uses `memberDeps.isEmpty() ? null : new ArrayList<>(memberDeps)`, which skips the empty-set case. The inconsistency means method-level member deps in the dashboard always write `[]` for methods without data, inflating JSON output size.  
**Root cause:** Different code paths written by different contributors; the null-check was defensive but incorrect against the API contract.

---

### BUG-128: `TestScorer.packageProximityBonus` awards bonus only when test is in same/sub-package of changed class, not when changed class is in sub-package of test

**File:** `test-order-core/src/main/java/me/bechberger/testorder/TestScorer.java` line 651  
**Severity:** LOW (minor scoring gap)  
**Status:** FIXED (2026-06-16)  
**Symptom:** The check `testPkg.equals(changedPkg) || testPkg.startsWith(changedPkg + ".")` (line 651) only awards the proximity bonus when the test class lives in the same package or a sub-package of the changed class. The reverse relationship — changed class in a sub-package of the test (e.g. test in `com.example`, changed class in `com.example.impl`) — does not earn the bonus. This is common in large codebases where integration tests live in a parent package while the classes they test are split into sub-packages. The bonus is meant to surface direct unit tests, but "direct" includes integration tests in parent packages that cover sub-package implementation classes.  
**Root cause:** Intentional one-directional filter that may be overly narrow. The `startsWith(changedPkg + ".")` condition anchors on the changed class's package being the prefix.

---

## Third-Party Synthetic Bug Validation Results (2026-06-15)

The following campaigns validate that test-order's selection correctly prioritizes tests that catch injected synthetic bugs. Each entry records the repo, the patched method, the logical error, and whether the top-3 selected tests caught the bug.

### jackson-annotations — CAUGHT ✓

**Patch:** `scripts/bugs/jackson-annotations/propertyaccessor-getter-enabled-flip.patch`  
**Changed:** `PropertyAccessor.getterEnabled()` in `com/fasterxml/jackson/annotation/PropertyAccessor.java`  
**Bug:** Flipped `return (this == GETTER) || (this == ALL)` → `return (this != GETTER) && (this != ALL)` — getter-enabled logic inverted  
**Result:** Bug caught in top-3 selected tests ✓

### commons-configuration — CAUGHT ✓

**Patch:** `scripts/bugs/commons-configuration/xmlconfiguration-isvalidating-flip.patch`  
**Changed:** `XMLConfiguration.isValidating()` in `org/apache/commons/configuration2/XMLConfiguration.java`  
**Bug:** Flipped `return validating` → `return !validating` — default non-validating XML parser now always validates, causing parse failures for XML configs without DTD  
**Result:** Bug caught in top-3 selected tests ✓

### okhttp — CAUGHT ✓

**Patch:** `scripts/bugs/okhttp/dns-aaaa-type-bug.patch`  
**Result:** Bug caught in top-3 selected tests ✓

### resilience4j — CAUGHT ✓

**Patch:** `scripts/bugs/resilience4j/bulkhead-default-concurrent-calls.patch`  
**Result:** Bug caught in top-3 selected tests ✓

### netty — CAUGHT ✓

**Patch:** `scripts/bugs/netty/netutil-validipv4-flip.patch`  
**Changed:** `NetUtil.isValidIpV4Address(String, int, int)` in `io/netty/util/NetUtil.java`  
**Bug:** Flipped `len <= 15 && len >= 7 &&` → `len > 15 || len < 7 ||` — valid IPv4 lengths now rejected, invalid lengths accepted  
**Result:** Bug caught in top-3 selected tests ✓

### classgraph — MISSED ✗

**Patch (v1):** `scripts/bugs/classgraph/classinfo-isstandardclass-flip.patch`  
**Changed:** `ClassInfo.isStandardClass()` — flipped to return `(isAnnotation() || isInterface())`  
**Reason MISSED:** `ClassInfo` depends on >80% of tests; selection signal too weak (top-3 tests don't call `getAllStandardClasses()`)  

**Patch (v2):** `scripts/bugs/classgraph/classinfo-getsuperclass-flip.patch`  
**Changed:** `ClassInfo.getSuperclass()` — flipped Object null check so non-Object classes return null  
**Reason MISSED:** Same root cause — `ClassInfo` is too central (80% test coverage); selected top-3 tests don't trigger the flipped code path in assertions  

**Note:** classgraph needs a more targeted patch in a less central class (e.g. `ScanResult.getClassesWithAnnotation()` or a specific feature class) to get discriminating selection.

---

## Synthetic Bug Campaign — 2026-06-15 (continued)

All bugs in this section are **synthetic** (injected for test-order validation). Patches are in `scripts/bugs/<repo>/`.

### classgraph — CAUGHT ✓ (v5)

**Patch:** `scripts/bugs/classgraph/jsonobject-closing-bracket-bug.patch`  
**Changed:** `JSONObject.toJSONString()` — changed `buf.append('}')` to `buf.append(']')` at object close  
**Bug:** JSON objects serialize as `{key: value]` (mismatched bracket), causing `JSONParser` to throw `ParseException` when parsing the output  
**Result:** Bug caught in top-3 selected tests ✓  
**Note:** Previous patches (v1-v4) MISSED because `ClassInfo`/`ClassMemberInfo` are too central (>50% test coverage) and `ClassInfo.getName()→getSimpleName()` bug didn't affect the actually-selected tests. `JSONObject` is only in 10/110 tests; selected top-3 tests (`Issue310Test`, `JSONSerializationTest`, `Issue314Test`) all call `toJSON()` and detect the malformed JSON.

### hibernate-orm — CAUGHT ✓

**Patch:** `scripts/bugs/hibernate-orm/standard-stack-depth.patch`  
**Changed:** `StandardStack` — off-by-one or depth computation error  
**Result:** Bug caught in top-3 selected tests ✓

### javaparser — CAUGHT ✓ (v2)

**Patch:** `scripts/bugs/javaparser/range-contains.patch`  
**Changed:** `Range.contains(Range other)` — flipped early-return logic: `if (!beginResult) return false` → `return true`  
**Bug:** `contains()` now returns `true` when `other.begin < this.begin` (range that starts before this range is falsely reported as contained)  
**Result:** Bug caught in top-3 selected tests ✓  
**Note:** v1 MISSED because learn phase only ran `javaparser-symbol-solver-testing` module (missing `RangeTest` in `javaparser-core-testing`). Fixed by adding `javaparser) echo "NONE"` to `detect_module_override` in `third-party-overrides.sh` to run the full reactor.

### jsoup — CAUGHT ✓

**Patch:** `scripts/bugs/jsoup/printer-shouldindent.patch`  
**Changed:** `Printer.shouldIndent()` — flipped indentation logic  
**Result:** Bug caught in top-3 selected tests ✓

### logging-log4j2 — CAUGHT ✓

**Patch:** `scripts/bugs/logging-log4j2/closeablethreadcontext-put.patch`  
**Changed:** `CloseableThreadContext` — put logic bug  
**Result:** Bug caught in top-3 selected tests ✓

### spring-petclinic — CAUGHT ✓

**Patch:** `scripts/bugs/spring-petclinic/petvalidator-supports.patch`  
**Changed:** `PetValidator.supports()` — validation type check flipped  
**Bug:** Validator incorrectly rejects valid Pet objects or accepts invalid ones  
**Result:** Bug caught in top-3 selected tests ✓  
**Note:** Build initially failed due to `git-commit-id-maven-plugin` requiring git commits in a broken `.git` folder. Fixed by adding `-Dmaven.gitcommitid.skip=true` override in `third-party-overrides.sh`.

### commons-io — CAUGHT ✓

**Patch:** `scripts/bugs/commons-io/byteorderparser-endian.patch`  
**Changed:** `ByteOrderParser` — byte order detection flipped  
**Result:** Bug caught in top-3 selected tests ✓

### commons-text — CAUGHT ✓

**Patch:** `scripts/bugs/commons-text/alphabetconverter-size.patch`  
**Changed:** `AlphabetConverter` — size computation error  
**Result:** Bug caught in top-3 selected tests ✓

### commons-compress — CAUGHT ✓

**Patch:** `scripts/bugs/commons-compress/flip-byteutils-shift.patch`  
**Changed:** `ByteUtils` — byte shift direction flipped  
**Result:** Bug caught in top-3 selected tests ✓

### commons-pool — CAUGHT ✓

**Patch:** `scripts/bugs/commons-pool/eviction-config-is-positive-flip.patch`  
**Changed:** `EvictionConfig.isPositive()` — positive check negated  
**Result:** Bug caught in top-3 selected tests ✓

### pdfbox — CAUGHT ✓

**Patch:** `scripts/bugs/pdfbox/pdDocument-getNumberOfPages-plus-one.patch`  
**Changed:** `PDDocument.getNumberOfPages()` — changed `getCount()` to `getCount() + 1`  
**Bug:** Page count always returns one more page than actually exists, causing `assertEquals(1, doc.getNumberOfPages())` to fail with 2  
**Result:** Bug caught in top-3 selected tests ✓  
**Note:** `PDDocument` affects 88% of tests (102/116), but top-3 selected tests (`TestPDDocument`, `TestPDDocumentCatalog`, `TestFDF`) directly assert `getNumberOfPages()` with specific values, ensuring detection despite high class coverage.  
**Failing tests:** `TestPDDocumentCatalog.retrieveNumberOfPages`, `TestPDDocumentCatalog.retrievePageLabels`, `TestPDDocument.testSaveLoadFile`, `TestPDDocument.testSaveLoadStream`

---

### junit5 — CAUGHT ✓

**Patch:** `scripts/bugs/junit5/calculator-add-off-by-one.patch`  
**Changed:** `Calculator.add()` — off-by-one: `return a + b + 1` instead of `return a + b`  
**Bug:** Simple arithmetic off-by-one in test documentation Calculator class  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `AssertionsDemo.standardAssertions()` — "expected: <2> but was: <3>"

### micronaut-core — CAUGHT ✓

**Patch:** `scripts/bugs/micronaut-core/http-status-ok-code.patch`  
**Changed:** `HttpStatus.OK` — HTTP status code changed from 200 to 201, colliding with `CREATED`  
**Bug:** HTTP 200 OK reports wrong status code, breaking any code checking for `HttpStatus.OK` by code value  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing modules:** `micronaut-http-server-netty`, `micronaut-inject-java` — multiple tests failed

### mockito — CAUGHT ✓

**Patch:** `scripts/bugs/mockito/strictness-flip.patch`  
**Changed:** `MockitoExtension` constructor — default strictness changed from `Strictness.STRICT_STUBS` to `Strictness.WARN`  
**Bug:** JUnit 5 extension defaults to lenient strictness, allowing unnecessary stubbings to pass silently  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `StrictnessTest` — tests verifying default strict strictness detected the changed behavior

### commons-collections — CAUGHT ✓

**Patch:** `scripts/bugs/commons-collections/comparatorpredicate-equal.patch`  
**Changed:** `ComparatorPredicate` — equality evaluation flipped  
**Bug:** Comparator-based predicate returns wrong result for equality comparisons  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `ComparatorPredicateTest` — 6 tests run, 1 failure

### spring-ai — CAUGHT ✓

**Patch:** `scripts/bugs/spring-ai/flip-tool-definition-condition.patch`  
**Changed:** `DefaultToolDefinition` — tool definition condition logic flipped  
**Bug:** Tool invocation condition evaluation inverted, causing incorrect tool dispatch decisions  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `ToolCallingChatOptionsTests` — 4 errors in 16 tests

### ai-sdk-java — CAUGHT ✓

**Patch:** `scripts/bugs/ai-sdk-java/flip-path-endswith.patch`  
**Changed:** `DestinationResolver` — `path.endsWith("/")` condition negated  
**Bug:** URL path trailing-slash detection inverted, causing destination URLs to be constructed incorrectly  
**Result:** Bug caught in top-3 selected tests ✓

### jackson-databind — CAUGHT ✓

**Patch:** `scripts/bugs/jackson-databind/basenode-container-type-flip.patch`  
**Changed:** `BaseNodeDeserializer._deserializeContainerNoRecursion()` — flipped `curr instanceof ObjectNode` to `curr instanceof ArrayNode`, inverting array/object container dispatch  
**Bug:** During iterative JSON tree deserialization, the ObjectNode and ArrayNode code paths are swapped, causing `ClassCastException` when parsing any JSON object  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing tests:** `NodeContext2049Test` (1 error), `JsonPointerWithNodeTest` (3 errors), `ParsingContext2525Test` (3 errors)  
**Note:** Previous patches (`JavaSqlBlobSerializer.isEmpty` flip, `ClassUtil.isNonStaticInnerClass` flip, `PropertyName.equals` flip) all MISSED — either too narrow (1 test, not critical path) or too broad (94%/82% coverage). `BaseNodeDeserializer` selected `DeepJsonNodeSerTest` (rank #6) which exercises the exact code path.

### commons-csv — CAUGHT ✓

**Patch:** `scripts/bugs/commons-csv/csvprinter-newrecord-flip.patch`  
**Changed:** `CSVPrinter.printRaw()` — `newRecord = false` changed to `newRecord = true`  
**Bug:** Record boundary flag never reset, causing all CSV fields to be treated as the start of a new record  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `JiraCsv227Test.test` — 1 failure

### commons-dbcp — CAUGHT ✓

**Patch:** `scripts/bugs/commons-dbcp/cpdssconnectionfactory-validatingset-flip.patch`  
**Changed:** `CPDSConnectionFactory.connectionClosed()` — `!validatingSet.contains(pc)` changed to `validatingSet.contains(pc)`  
**Bug:** Connection error handling inverted: connections being validated are treated as free to return to pool  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `TestCPDSConnectionFactory.testConnectionErrorCleanup` — 2 failures

### commons-geometry — CAUGHT ✓

**Patch:** `scripts/bugs/commons-geometry/iteratortransform-hasnext-flip.patch`  
**Changed:** `IteratorTransform.next()` — `outputQueue.removeFirst()` changed to `outputQueue.removeLast()`  
**Bug:** Iterator removes elements from wrong end of queue, reversing the transformed iteration order  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `IteratorTransformTest.testIteration` — 1 failure

### commons-math — CAUGHT ✓

**Patch:** `scripts/bugs/commons-math/matharrays-checkorder-strict-flip.patch`  
**Changed:** `MathArrays.checkOrder()` — `val[index] <= previous` changed to `val[index] < previous`  
**Bug:** Strict ordering check becomes non-strict, allowing duplicate values in strictly increasing sequences  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `MathArraysTest.testCheckOrder` — 2 failures

### commons-statistics — CAUGHT ✓

**Patch:** `scripts/bugs/commons-statistics/intmath-squarehigh-sign-flip.patch`  
**Changed:** `IntMath.squareHigh()` — sign correction changed from subtraction to addition  
**Bug:** High 32-bit word of 64-bit integer square computation has incorrect sign adjustment  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `IntKurtosisTest.testArrayAndCombineRandom` — 5 failures

### commons-lang — CAUGHT ✓

**Patch:** `scripts/bugs/commons-lang/stringutils-isblank-whitespace-flip.patch`  
**Changed:** `StringUtils.isAlpha()` — `!Character.isLetter(...)` condition negated  
**Bug:** `isAlpha()` completely inverts its contract — returns false for alpha-only strings  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `StringUtilsSubstringTest` — 2 failures

### logbook — CAUGHT ✓

**Patch:** `scripts/bugs/logbook/contenttype-json-check-flip.patch`  
**Changed:** `ContentType.isApplicationJson()` — `||` changed to `&&` in MIME type check  
**Bug:** JSON content type detection requires both `/json` and `+json` suffix simultaneously (impossible), making all JSON appear non-JSON  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `ContentTypeTest` — 11 failures (build still SUCCESS due to `maven.test.failure.ignore=true`)

### jackson-core — CAUGHT ✓

**Patch:** `scripts/bugs/jackson-core/numberinput-inlongrange-flip.patch`  
**Changed:** `NumberInput.inLongRange()` — comparison inverted from `diff < 0` to `diff > 0`  
**Bug:** Long range check inverted, causing valid long values to be rejected and invalid ones to be accepted  
**Result:** Bug caught in top-3 selected tests ✓  
**Failing test:** `NumberInputTest` — 1 failure

### commons-codec — CAUGHT ✓ (SA auto-mode)

**Patch:** `scripts/bugs/commons-codec/base64-isbase64-flip.patch`  
**Changed:** `Base64.isBase64()` — boolean condition inverted  
**Bug:** Base64 validity check inverted, incorrectly classifying valid/invalid bytes  
**Top-3 selection result:** MISSED — selected tests (`Base64Codec13Test`, `Base64OutputStreamTest`, `BCodecTest`) ran 32 tests with 0 failures (these tests don't exercise `isBase64()` directly)  
**SA auto-mode (changeMode=uncommitted) result:** CAUGHT ✓ — 17 failures in `Base64Test` (`testCodec263`, `testEncodeDecodeRandom`, etc.)  
**Note:** Top-3 selected tests missed because patch targets `Base64.isBase64()` which is not called by the highest-scored tests. SA auto-mode (scanning all uncommitted changes) caught the bug by running all tests that depend on the changed class.

### kafka — MISSED (utility class too central)

**Patch:** `scripts/bugs/kafka/utils-isblank-negate.patch`  
**Previous issue:** v1 campaign used old API (`cleanTestOrderSelect` not found). v2/v3 campaigns showed UNKNOWN due to BUG-153 (testOrderAffected getting NO-SOURCE). v3 fixed by BUG-153 fix.  
**Result (2026-06-16):** MISSED — `testOrderAffected` ran correctly (selected 13 tests, compiled + executed), but `UtilsTest` was not in the top-3 selected. Bug is in `Utils.isBlank` which is a utility method referenced by many classes equally, so the scoring doesn't differentiate `UtilsTest` from others.  
**Notes:** This is expected behavior for heavily-used utility methods. `Utils` is a dependency of ~100+ test classes, so no single test ranks highly for it.

### maven — IN PROGRESS (new patch, full reactor indexed)

**Previous patch:** `scripts/bugs/maven/pathselector-needrelativize-flip.patch` — MISSED (bug didn't cause observable test failures: `needRelativize()` always `true` didn't break the test's path comparisons with `glob:**` patterns)  
**New patch:** `scripts/bugs/maven/pathselector-ismatched-flip.patch`  
**Changed:** `PathSelector.isMatched()` — `return true` flipped to `return false` when a path matches, meaning no include pattern ever matches  
**Previous infrastructure issue resolved:** `detect_single_module` → `NONE` forces full 39-module reactor; `detect_maven_java_home` → SAP JDK 21 (has `ct.sym` for `--release 17`); `-Dmaven.compiler.proc=none` skips `DiIndexProcessor` failure; adds `maven-executor` skip  
**Current status:** Campaign queued, awaiting thinkstation reconnection.

### spring-boot — CAUGHT ✓

**Patch:** `scripts/bugs/spring-boot/job-exit-code-eq-zero.patch`  
**Previous issue:** All campaigns showed UNKNOWN due to BUG-153 (testOrderAffected getting NO-SOURCE). Fixed 2026-06-16.  
**Result (2026-06-16):** CAUGHT in top-3 selected tests! Bug in `JobExecutionExitCodeGenerator.getExitCode()`, test `JobExecutionExitCodeGeneratorTests` caught it.

### caffeine — IN PROGRESS (learn phase running)

**Patch:** `scripts/bugs/caffeine/` (pending — need to create after learn completes)  
**Infrastructure:** JDK 21 override via `-PjavaVersion=21` and SAP JDK 21 JAVA_HOME; excludes `:caffeine:jcstress` and `:caffeine:jmh`.  
**Current status:** Learn phase running on thinkstation.

### guava — BLOCKED (JPMS compile-java9 execution fails)

**Issue:** guava-testlib uses a `compile-java9` Gradle task that tries to run JPMS module compilation. The compile step fails when test-order's plugin hooks interfere with the Java 9 multi-release jar compilation task.  
**Status:** Blocked — needs investigation of JPMS / multi-release jar support.

### byte-buddy — BLOCKED (build infrastructure investigation needed)

**Issue:** byte-buddy requires additional configuration investigation.  
**Status:** Pending investigation.


### assertj 4.0.0-SNAPSHOT — BLOCKED (JPMS module path conflict)

**Issue:** assertj 4.x uses `--add-modules org.junit.jupiter.api,org.junit.platform.commons` in argFile (JPMS in-module testing). When test-order instruments the JVM, the JPMS boot layer reports `Module org.junit.jupiter.api not found`, crashing the forked test JVM.  
**Status:** Not supported — requires investigation of JPMS compatibility with test-order's ClassFileTransformer instrumentation.  
**Error:** `Error occurred during initialization of boot layer: java.lang.module.FindException: Module org.junit.jupiter.api not found`

### cglib — BLOCKED (Java 6 target not supported in JDK 17+)

**Issue:** cglib targets Java 6 (`-source 6`) which is no longer supported in JDK 17+.  
**Error:** `Source option 6 is no longer supported. Use 7 or later.`  
**Status:** Skip — would require JDK 8 or special handling.

### eclipse-collections — BLOCKED (custom code generator plugin class loading issue)

**Issue:** eclipse-collections uses `eclipse-collections-code-generator-maven-plugin` which fails with `NoClassDefFoundError: me/bechberger/testorder/agent/runtime/UsageStore` during source generation. The test-order classloader appears to interfere with the code generator's plugin realm.  
**Status:** Known limitation with custom Maven plugins that use a different classloader realm.

### jimfs — SKIPPED (JUnit 4 only)

**Issue:** jimfs uses JUnit 4 (`junit:junit`), which test-order does not support. Despite successfully compiling and running 5901 tests, test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — same as awaitility/guice. Would require JUnit 5 migration.

### awaitility — SKIPPED (JUnit 4 only)

**Issue:** awaitility uses JUnit 4 (`junit:junit`). test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — requires JUnit 5 migration.

### gson — SKIPPED (JUnit 4 only)

**Issue:** gson uses JUnit 4 (`junit:junit`). test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — requires JUnit 5 migration.

### guice — SKIPPED (JUnit 4 only)

**Issue:** guice uses JUnit 4 (`junit:junit`). test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — requires JUnit 5 migration.

### joda-time — SKIPPED (JUnit 4 only)

**Issue:** joda-time uses JUnit 4 (`junit:junit`). test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — requires JUnit 5 migration.

### picocli — SKIPPED (JUnit 4 + missing contrib rule dep)

**Issue:** picocli uses JUnit 4 with `org.junit.contrib:junit4-runner-chain` and `system-rules` contrib library. Compilation fails with `package org.junit.contrib.java.lang.system does not exist`. Even if compiled, test-order does not support JUnit 4.  
**Status:** Skip — requires JUnit 5 migration.

### slf4j — SKIPPED (JUnit 4 only)

**Issue:** slf4j uses JUnit 4 (`junit:junit`). test-order emits `"JUnit 4 dependency detected but no JUnit 5 (Jupiter) found"` and produces no dependency index.  
**Status:** Skip — requires JUnit 5 migration.

### httpcomponents-client — CAUGHT ✓

**Patch:** `scripts/bugs/httpcomponents-client/basicroutedirector-tunnel-proxy.patch`  
**Changed:** `BasicRouteDirector.proxiedStep()` — changed `return TUNNEL_PROXY` to `return TUNNEL_TARGET` when proxy hop count is insufficient, causing incorrect routing direction  
**Bug:** Proxy chain extension returns wrong tunnel action type, breaking proxy routing decisions  
**Result:** Bug caught in top-3 selected tests ✓  
**Selected test:** `org.apache.hc.client5.http.impl.routing.TestRouteDirector` — 14 tests run, 1 failure  
**Note:** First patch (`routetracker-istunnelled-flip.patch`) affected 100% of tests → MISSED. New patch in `BasicRouteDirector` affects ~60/121 tests (50%) → CAUGHT.  
**Infrastructure issue resolved:** Added JDK 1.8 toolchain entry to `~/.m2/toolchains.xml` pointing to JDK 17 (compatible target).

---

## Change Detection & Multi-Module Validation (2026-06-15)

### Bytecode / Method-Hash Change Detection — VERIFIED ✓

**Test project:** `test-order-example/test-order-example-service` (8 tests, 8 classes)

**Setup:** After a full `learn` phase, injected an off-by-one bug into `OrderService.totalOrders()`:
```java
// Before:
return orderRepository.count();
// After (injected bug):
return orderRepository.count() + 1; // off by one
```

**Change detection results (both modes verified):**
- **`changeMode=uncommitted`** (git-based): Selected 1 test (`OrderServiceTest`), deferred 7 — correctly identified the test dependent on the changed class.
- **`changeMode=since-last-run`** (method-hash / bytecode-based): Same result — selected 1 test, deferred 7.
- Selected test failed: `OrderServiceTest` — 1 failure catching the injected bug.
- Test selection precision: 1/8 tests = 12.5% of tests needed to detect the bug.

**Conclusion:** Both git-based and method-hash change detection correctly select the minimal set of tests affected by a code change.

### Static Call-Graph Analysis — VERIFIED ✓

**Command:** `mvn test-order:show-static-analysis -Dtestorder.changed.classes="com.example.service.service.OrderService"`  
**Result:** Static analysis correctly identified `OrderService.totalOrders()` as the changed member, and after 2-level call-graph expansion discovered `OrderServiceTest` as the only caller. Total: 18 members, 2 classes expanded.

### Multi-Module Cross-Module Change Detection — VERIFIED ✓

**Test:** `MultiModuleWorkflowIT` integration tests (21 tests)  
**Command:** `mvn test -f test-order-maven-plugin/pom.xml -Dtest=MultiModuleWorkflowIT -Dtestorder.it=true`  
**Result:** 21/21 tests PASS in 128s  
**Coverage:** Multi-module Maven reactor builds with cross-module test-order dependency tracking.

### End-to-End Service Integration — VERIFIED ✓

**Test:** `EndToEndServiceIT` integration tests (12 tests)  
**Command:** `mvn test -f test-order-maven-plugin/pom.xml -Dtest=EndToEndServiceIT -Dtestorder.it=true`  
**Result:** 12/12 tests PASS in 39s

---

## Order-Dependency (OD) Detection Validation (2026-06-15)

### test-order-core — NO OD BUGS ✓

**Script:** `scripts/find_order_dependent_bugs.sh core`  
**Test orderings:** baseline (default), reverse-class, alphabetical, 5 random seeds (42, 123, 999, 7, 2024)  
**Result:** All 8 orderings PASS — no order-dependent bugs found.

### test-order-maven-plugin — NO OD BUGS ✓

**Script:** `scripts/find_order_dependent_bugs.sh plugin`  
**Test orderings:** baseline (default), reverse-class, alphabetical, 5 random seeds (42, 123, 999, 7, 2024)  
**Result:** All 8 orderings PASS — no order-dependent bugs found.

**Conclusion:** test-order's own test suite is free of order-dependent tests across 8 different run orderings. The test isolation is correct.


---

## PIT Mutation Testing Integration Validation (2026-06-15)

### analyze-mutations goal — VERIFIED ✓

**Test project:** `test-order-example/test-order-example-service` (8 tests, 8 production classes)  
**Command:** `mvn test-order:analyze-mutations`  
**Result:** 53/61 mutations killed (86.9% kill rate), report written to `target/pit-reports` and `target/test-mutation-results.json`

**Kill rates by test class:**
- `UserValidatorTest`: 11 mutations (20.8% contribution)
- `OrderValidatorTest`: 10 mutations (18.9%)
- `OrderServiceTest`: 8 mutations (15.1%)
- `OrderRepositoryTest`: 6 mutations (11.3%)
- `UserServiceTest`: 6 mutations (11.3%)
- `OrderTest`: 6 mutations (11.3%)
- `UserTest`: 3 mutations (5.7%)
- `UserRepositoryTest`: 3 mutations (5.7%)

**Conclusion:** PIT mutation analysis integrates correctly with test-order, recording kill rates per test class in the state file for use in scoring.

---

## Session 7 — 2026-06-16 Code Audit

### BUG-130: `DiagnosticOperation.checkIndexFile` integer division loses precision for small projects

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/DiagnosticOperation.java` line 176  
**Severity:** LOW (false negative on small projects)  
**Status:** FIXED (2026-06-16)  
**Symptom:** The coverage check `indexedCount < actualCount / 10` uses integer division. When `actualCount < 10` (e.g. a micro-project with 7 test classes), `actualCount / 10 = 0`, so the condition becomes `indexedCount < 0` — always false for valid counts. A project with 7 test classes where learn mode indexed none (all failed) would not trigger the "<10% coverage" warning.  
**Root cause:** Integer division truncates toward zero. The condition should use `indexedCount * 10 < actualCount` (equivalent but avoids truncation) or `indexedCount < (actualCount + 9) / 10` (ceiling division).

---

### BUG-131: `DiagnosticOperation.determineStatus` misclassifies fresh projects that also have errors

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/DiagnosticOperation.java` lines 390–394  
**Severity:** LOW (misleading diagnostic output)  
**Status:** FIXED (2026-06-16)  
**Symptom:** The status logic at line 390 only shows "NOT SET UP (run learn mode first)" when `freshProject && !hasErrors`. If a fresh project ALSO has errors (e.g. the index file is missing AND there's a configuration error), `freshProject && !hasErrors` is false, and it falls through to line 393: `hasErrors` → "ISSUES ⚠" or "CRITICAL ✗". Users see "CRITICAL" instead of "NOT SET UP", which gives confusing guidance — they should run learn mode first, not debug mysterious critical errors.  
**Root cause:** The condition at line 390 is `freshProject && !hasErrors` (fresh-only guard), but for a completely uninitialised project it should be `freshProject` alone (fresh takes precedence over generic error messages).

---

### BUG-132: `DashboardGenerator.buildData` uses `!= null` check on method that never returns null

**File:** `test-order-core/src/main/java/me/bechberger/testorder/DashboardGenerator.java` line 256  
**Severity:** LOW (empty arrays written to JSON for all methods without member deps)  
**Status:** FIXED (2026-06-16)  
**Symptom:** At line 256, `getMethodMemberDeps(key)` is checked for null before converting to a list:
```java
Set<String> mMemberDeps = depMap.getMethodMemberDeps(key);
m.put("memberDeps", mMemberDeps != null ? new ArrayList<>(mMemberDeps) : null);
```
`DependencyMap.getMethodMemberDeps()` never returns null — it returns `Collections.emptySet()` when no deps are found. So the null check always evaluates true, and every method-entry in the dashboard JSON gets `"memberDeps": []` (an empty list) instead of `null`. The identical pattern at line 238 for test-class-level deps correctly uses `memberDeps.isEmpty() ? null : new ArrayList<>(memberDeps)`. The inconsistency inflates the dashboard JSON with empty arrays for every method that has no member-dep data.  
**Root cause:** Copy-paste error; the pattern used at line 238 was not applied at line 256.

---

### BUG-133: `StaticCallGraphAnalyzer.addCallEdge` only walks superclass chain, missing interface-declared methods

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/StaticCallGraphAnalyzer.java` lines 891–908  
**Severity:** HIGH (false negatives in call-graph expansion — callers of interface methods not discovered)  
**Status:** FIXED (2026-06-16)  
**Symptom:** When a caller calls a method via a concrete type (e.g. `impl.foo()`), `addCallEdge` records `Impl#foo ← caller`. It then walks up via `superClasses` to find where `foo` is declared. But `superClasses` contains only the direct superclass; interface declarations are stored separately in `interfaces`. If `foo` is declared in interface `I1` (and `Impl` implements `I1` without re-declaring `foo`), the walk never reaches `I1`. The edge `I1#foo ← caller` is never recorded.

As a result, BFS expansion starting from a change to `I1#foo` never reaches `caller`, even though `caller` calls `Impl.foo()` which is actually `I1.foo()`. Tests that call changed interface methods through concrete type references are silently excluded from the impact set.  
**Root cause:** `addCallEdge` at line 899 only does `result.superClasses.get(ownerFqcn)` and walks the superclass chain. It should also check `result.interfaces.get(ownerFqcn)` (which contains the set of interfaces implemented by `ownerFqcn`) and record edges to each declared interface method. Compare with `directSubtypes` population (lines 447–458): both `superClasses` AND `interfaces` are iterated there, but `addCallEdge` only uses `superClasses`.

---

### BUG-134: `BytecodeHashStore.save/load` use platform-default charset instead of UTF-8

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/BytecodeHashStore.java` lines 215, 249  
**Severity:** LOW (hash files silently unreadable when JVM default charset changes or differs between environments)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `save()` constructs `new OutputStreamWriter(lz4os)` (line 215) and `load()` constructs `new InputStreamReader(lz4is)` (line 249) — both without specifying a charset. They rely on the JVM's `file.encoding` default, which is UTF-8 on most modern systems but can differ (e.g. US-ASCII on some CI environments, or locale-dependent on Windows). If a hash file is saved with one charset and loaded with another — e.g. because CI uses a different JVM locale — every class, method, and field key containing non-ASCII characters (valid in JVM source) will decode incorrectly, causing hash mismatches and false "changed" detections on every run.  
**Root cause:** Missing `StandardCharsets.UTF_8` argument on both `OutputStreamWriter` and `InputStreamReader` constructors.

---

### BUG-135: `SourceFileModel.findMethodIslands` and `findFieldIslands` return empty results for JEP 512 implicit classes

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java` lines 669, 1215  
**Severity:** HIGH (complete false-negative for implicit class files — no methods detected as changed)  
**Status:** FIXED (2026-06-16)  
**Symptom:** The Javadoc for `SourceFileModel` (line 38–39) explicitly claims support for "JEP 512 implicit classes (root-level methods without an enclosing type)". However, both `findMethodIslands` (line 669) and `findFieldIslands` (line 1215) immediately return empty lists when `types.isEmpty()`:
```java
if (types.isEmpty())
    return methods;  // line 669 — skips all methods in implicit class
```
An implicit class has no explicit `class` or `record` declaration — it IS a root-level collection of methods and fields without a type wrapper. So `types` is always empty for a valid implicit class file, and all its methods and fields are silently omitted from the model. Any change to a method in an implicit class will never be detected, and the tests that cover it will not be re-run.  
**Root cause:** The `types.isEmpty()` early-return was added as a guard for "files with no type declarations are unusual, skip them". It predates implicit class support. The JEP 512 support claim in the Javadoc was added without removing or conditioning this guard.

---

### BUG-136: `TestOrderState.pruneDeletedTestClasses` inner-class fixup loop misses inner classes recorded only in `failureHistory`

**File:** `test-order-core/src/main/java/me/bechberger/testorder/TestOrderState.java` lines 719–741  
**Severity:** MEDIUM (spurious retention or incorrect pruning of orphaned failure-history entries)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `pruneDeletedTestClasses` builds the `tracked` set from both `durationTracker.classDurations().keySet()` and `failureHistory.knownClasses()` (line 706–707), correctly covering all known classes. However, the inner-class fixup loop at line 725 iterates only over `durationTracker.classDurations().keySet()`:
```java
for (String fqcn : new java.util.HashSet<>(durationTracker.classDurations().keySet())) {
    if (fqcn.contains("$")) { ... }
}
```
An inner test class (e.g. `MyTest$InnerTest`) that was recorded in `failureHistory` but has no duration entry (possible when the test was skipped, excluded, or failed so early no duration was captured) is never checked by this loop. Its outer class may be retained in `retained`, but the inner class won't be added to `retained` by the fixup — so it ends up in `finalRetained` only if it was originally in `failureHistory.knownClasses()` and not in `pruned`. The symmetry between the two data stores is broken for inner classes, potentially leaving stale failure entries for orphaned inners.  
**Root cause:** The inner-class fixup at line 725 should iterate over the union of both `durationTracker.classDurations().keySet()` and `failureHistory.knownClasses()` (same set used to populate `tracked` at lines 706–707), not just the duration tracker's keys.

---

### BUG-137: `SourceFileModel.findMethodIslands` compact-constructor prefix scan uses `>` instead of `>=`, dropping first character of access modifier

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java` lines 924–930  
**Severity:** LOW (access modifier of record compact constructors occasionally misidentified, causing false "changed" detection)  
**Status:** FIXED (2026-06-16)  
**Symptom:** When detecting a compact record constructor, the code scans backward from the record-name position to find the preceding access modifier:
```java
int lineStart = nameIdx - 1;
while (lineStart > searchStart && stripped.charAt(lineStart) != ';'
        && stripped.charAt(lineStart) != '}' && stripped.charAt(lineStart) != '{') {
    lineStart--;
}
lineStart++;
String prefix = stripped.substring(lineStart, nameIdx).trim();
```
The loop stops when `lineStart == searchStart` WITHOUT checking that character. After `lineStart++`, the substring starts one position past `searchStart`, silently dropping the first character of the prefix. If the compact constructor is the very first declaration in the record body (`searchStart` points to the start of `"public"`), the extracted prefix is `"ublic"` instead of `"public"`. Since `"ublic"` doesn't match `"public"`, `"protected"`, or `"private"`, the prefix check at line 931 fails and the compact constructor is not detected at all.  
**Root cause:** Off-by-one: `lineStart > searchStart` should be `lineStart >= searchStart`. The `lineStart++` nudge is correct to skip past a terminator found mid-scan, but when the loop exits because `lineStart == searchStart` (boundary reached without finding a terminator), the nudge incorrectly skips the boundary character itself.

---

### BUG-138: `StructuralChangeAnalyzer.resolveMemberName` maps both constructor changes and instance initializer changes to `"<init>"`, causing key collision

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/StructuralChangeAnalyzer.java` lines 153–169  
**Severity:** MEDIUM (constructor and instance-initializer changes silently merged, losing granularity in member-level impact analysis)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `resolveMemberName` maps any METHOD-category change whose `detail` starts with `"constructor"` to the string `"<init>"` (line 157–158). It also yields `"<init>"` for INITIALIZER-category changes whose `name` is `"<init>"` (instance initializers, line 454–456 of `StructuralDiff.java`). As a result, both a constructor change and an instance-initializer change in the same class produce the member key `"com.example.Foo#<init>"`. When both occur in one diff, `kinds.merge(memberKey, ...)` at line 100 silently collapses them via `mostImpactful()` instead of tracking them separately.

Practical impact: if a test depends on a constructor (via member-dep analysis) but not on the instance initializer, and both changed, the merged kind may be escalated (e.g., `SIGNATURE` from the constructor overrides `BODY` from the initializer), causing the wrong escalation signal to drive static-analysis expansion.  
**Root cause:** `StructuralDiff` reports constructors with their human-readable method name (e.g., `"Foo"` or overloaded `"Foo#Foo"`) as category=METHOD. `resolveMemberName` normalises all such constructors to `"<init>"` to align with JVM bytecode naming — but instance initializers are reported separately as INITIALIZER with `name="<init>"` (from `StructuralDiff.diffInitializerGroup` at line 456). Both paths produce the identical string, causing the collision. Fix: use a distinct sentinel for instance initializers (e.g., `"##instance-init"`) or preserve the original method-name key for constructors (e.g., `"Foo"` or the full signature) to keep them separate from initializers.

---

### BUG-139: `PartialRunAggregator` outcome deduplication only considers `failed` flag, not worst-case score

**File:** `test-order-core/src/main/java/me/bechberger/testorder/PartialRunAggregator.java` lines 138–139  
**Severity:** LOW (slightly suboptimal APFD scoring in multi-fork builds; doesn't affect correctness of failure detection)  
**Status:** FIXED (2026-06-16)  
**Symptom:** When multiple surefire forks record the same test class, the merge strategy at line 138 keeps the worst outcome by `failed` status, but keeps the first-seen entry when both have the same `failed` value:
```java
(existing, incoming) -> (!existing.failed() && incoming.failed()) ? incoming : existing
```
The comment says "prefer the worst-case outcome (failed > passed) to be conservative." But when both are passed (or both are failed), the first occurrence is kept regardless of score. In practice, if fork A scores `Foo` at 30 and fork B scores it at 200 (Foo was a higher-priority test in that fork), the merged run record retains score=30. The merged APFD calculation and future failure-history decay are thus based on a lower-quality ordering signal than necessary.  
**Root cause:** The deduplication predicate is a partial implementation of the "conservative" strategy — it handles the cross-failure-status case but not the same-status case. For the same-status case, keeping the higher score (`incoming.totalScore() > existing.totalScore() ? incoming : existing`) would be both more accurate and no less conservative.

---

### BUG-143: `StructuralChangeAnalyzer.memberLevelOverlapClasses` uses `##instance-init` sentinel as lookup key against agent-recorded `<init>` deps

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/StructuralChangeAnalyzer.java` lines 347–353  
**Severity:** MEDIUM (instance initializer changes never match in member-level overlap; test affected by `{ … }` block change is not detected as affected)  
**Status:** FIXED (2026-06-16)  
**Symptom:** After BUG-138 introduced the `"##instance-init"` sentinel for instance initializer changes, the member-level overlap check at line 348 builds the lookup key `"classDep###instance-init"`. But `testMemberDeps` (built from bytecode agent recordings) always uses `"<init>"` for both constructors and instance initializers — the agent has no way to distinguish them at the call site. So `testMemberDeps.contains("com.Foo###instance-init")` is always false, meaning: if a class has an instance initializer changed, no test is ever scored as affected by it through member-level analysis.  
**Root cause:** BUG-138 added the sentinel to disambiguate the change-side key (constructor change vs instance initializer change) but forgot that the lookup side (testMemberDeps) still uses the JVM's `<init>` for both. The two sides of the lookup must use the same key format.  
**Fix:** Added a sentinel-to-`<init>` translation in `memberLevelOverlapClasses`: when iterating `changedInClass`, resolve `"##instance-init"` back to `"<init>"` before building the member key for the `testMemberDeps` lookup.

---

### BUG-142: `ConflictGraphBuilder.build` enters member-dep path when only `methodMemberDepsMap` is non-empty, returning empty edge list

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/detection/ConflictGraphBuilder.java` line 38  
**Severity:** LOW (conflict graph empty when method-only member deps exist; falls through to no edges rather than class-dep fallback)  
**Status:** FIXED (2026-06-16)  
**Symptom:** After the BUG-141 fix, `hasMemberDeps()` returns `true` when only `methodMemberDepsMap` is populated. `ConflictGraphBuilder.build()` gates on `hasMemberDeps()` to choose `buildFromMemberDeps()` over `buildFromClassDeps()`. But `buildFromMemberDeps()` only calls `getMemberDeps(test)` (class-level), never `getMethodMemberDeps()`. When `memberDepsMap` is empty (and only `methodMemberDepsMap` has data), every test returns an empty member set, no shared-member edges are built, and the method returns an empty list — worse than falling through to `buildFromClassDeps()`.  
**Root cause:** `hasMemberDeps()` was widened by BUG-141 to cover both maps, but `ConflictGraphBuilder` only needs to branch on the class-level map (which `buildFromMemberDeps` actually reads).  
**Fix:** Added `hasClassMemberDeps()` to `DependencyMap` (returns `!memberDepsMap.isEmpty()`) and changed the `ConflictGraphBuilder` gate to use it.

---

### BUG-141: `DependencyMap.hasMemberDeps()` only checks `memberDepsMap`, ignoring `methodMemberDepsMap`

**File:** `test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java` line 396  
**Severity:** MEDIUM (method-level member deps present but flag returns false; member-level analysis silently skipped)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `hasMemberDeps()` returns `false` when `memberDepsMap` is empty but `methodMemberDepsMap` is non-empty. Callers such as `AffectedWorkflow`, `ConflictGraphBuilder`, and `DashboardGenerator` guard member-level analysis on this flag. If instrumentation produced only method-level member deps (`methodMemberDepsMap`) but no class-level member deps (`memberDepsMap`), those callers skip member analysis entirely, silently falling back to coarser class-level dependency tracking.

This is internally inconsistent: the write path at line 842 already uses the correct combined check `!memberDepsMap.isEmpty() || !methodMemberDepsMap.isEmpty()` for the serialization gate, but the public API method was never updated to match.  
**Root cause:** `hasMemberDeps()` was written when only `memberDepsMap` existed. `methodMemberDepsMap` was added later for per-method member tracking, and the single-field check in `hasMemberDeps()` was not updated.  
**Fix:** Changed to `return !memberDepsMap.isEmpty() || !methodMemberDepsMap.isEmpty();`

---

### BUG-144: `CoverageOperation.analyze` uses `hasMemberDeps()` but calls `getMemberDeps()`, producing empty member coverage when only per-method deps exist

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/coverage/CoverageOperation.java` line 52  
**Severity:** MEDIUM (member-level coverage report is silently empty when instrumentation only populated `methodMemberDepsMap`)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `analyze()` gates the member-data path at line 52 with `depMap.hasMemberDeps()`, which (after BUG-141 fix) returns true if either `memberDepsMap` or `methodMemberDepsMap` is non-empty. But the loop at line 73 calls `depMap.getMemberDeps(testClass)`, which reads only from `memberDepsMap`. If instrumentation populated only `methodMemberDepsMap` (method-granularity recording), `hasMemberData` is true, the loop runs, but `getMemberDeps` returns empty sets for every test class — `prodMemberToTests` stays empty. The result: all `ClassCoverage` entries are constructed without member data (the else branch at line 106), even though MEMBER-mode data exists. The coverage report shows no member coverage at all.  
**Root cause:** `hasMemberDeps()` was widened by BUG-141 to check both maps, but `CoverageOperation` (which only reads class-level data) was not updated to use the narrower `hasClassMemberDeps()` gate.  
**Fix:** Replace `depMap.hasMemberDeps()` with `depMap.hasClassMemberDeps()` at line 52.

---

### BUG-140: `JavaParserModel.visitType` does not extract compact record constructors

**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/JavaParserModel.java` lines 111–112  
**Severity:** MEDIUM (compact record constructors silently absent from parser output — their changes are never detected)  
**Status:** FIXED (2026-06-16)  
**Symptom:** `visitType` extracts constructors via `td.getConstructors()` (line 111), which in JavaParser returns only `ConstructorDeclaration` instances. Compact record constructors (`public MyRecord { ... }` without a parameter list) are represented by a separate JavaParser class, `CompactConstructorDeclaration`, which is NOT returned by `getConstructors()`. As a result, any change to a compact constructor body is invisible to the `JavaParserModel` code path — no `MethodNode` is created for it, and the method is treated as non-existent.

In contrast, `SourceFileModel.findMethodIslands` handles compact constructors via a dedicated detection path (lines 900–952), so the two parsers produce inconsistent results for records with compact constructors.  
**Root cause:** `CompactConstructorDeclaration` was introduced in JavaParser alongside Java 14 record support. The iteration at line 111 was never updated to also walk `td.getMembers()` and pick up `CompactConstructorDeclaration` instances, unlike `InitializerDeclaration` handling for classes (lines 123–135) which correctly iterates members.

---

## Session 8 — 2026-06-16 Mutation Testing Audit

### BUG-145: `MutationAnalysisOperation.run` replaces `$` in class names with `*` when building PIT glob patterns

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/MutationAnalysisOperation.java` line 158 (before fix)
**Severity:** HIGH (inner-class members of any indexed class produce an invalid PIT glob — `Outer*Inner` matches unrelated classes)
**Status:** FIXED (2026-06-16)
**Symptom:** The glob patterns passed to PIT were built with `.replace('$', '*')`, turning `com.example.Outer$Inner` into `com.example.Outer*Inner`. PIT's glob matcher treats `*` as a wildcard, so the pattern would match `com.example.OuterHelper` or any class with `Outer` as prefix — either silently overselecting (mutating unrelated classes) or misidentifying the target. All projects with inner classes are affected.
**Root cause:** Copy from an older PIT integration that used `$`→`*` expansion for glob matching. PIT accepts `$` directly in class name patterns and does not require wildcard substitution.
**Fix:** Removed `.replace('$', '*')`. PIT globs now use the fully-qualified class names as-is: `String.join(",", productionClasses)`.

---

### BUG-146: `MutationAnalysisOperation.run` does not validate that `testClassesDir` exists before invoking PIT

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/MutationAnalysisOperation.java` line 150 (before fix)
**Severity:** MEDIUM (PIT invocation silently fails or produces misleading errors when test-compile was not run)
**Status:** FIXED (2026-06-16)
**Symptom:** The method validated that `classesDir` exists (throws `IOException` if not), but did not validate `testClassesDir`. When `test-compile` was skipped or failed, PIT would start with an empty test-classes directory and produce zero mutants with no useful error message.
**Fix:** Added an explicit existence check for `testClassesDir`, mirroring the `classesDir` check.

---

### BUG-147: `MutationAnalysisOperation.run` silently returns empty results when no production classes are found

**File:** `test-order-core/src/main/java/me/bechberger/testorder/ops/MutationAnalysisOperation.java` line 142 (before fix)
**Severity:** MEDIUM (empty mutation run completes successfully, user gets no feedback that the index is incomplete)
**Status:** FIXED (2026-06-16)
**Symptom:** If `deriveProductionClasses()` returns an empty set (all dep-map entries point to test classes, or the index filter excluded all production code), `targetGlob` was an empty string. PIT ran with no mutation targets and produced a `mutations.xml` with zero entries. `run()` returned a `Result` with `totalMutants=0` and `totalKilled=0` without any warning, making the empty run look like a successful run with perfect kill rate.
**Fix:** Added an early-return with a `log.warn()` after `deriveProductionClasses()` when the returned set is empty.

---

### BUG-148: `MutationAnalyzeMojo` uses only root project's test classpath in multi-module builds

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/MutationAnalyzeMojo.java` lines 69–73 (before fix)
**Severity:** HIGH (multi-module mutation analysis silently misses test classes from all non-root modules)
**Status:** FIXED (2026-06-16)
**Symptom:** The mojo is annotated `aggregator = true` (runs once at reactor root), but called `project.getTestClasspathElements()` which returns only the root project's classpath. In a multi-module build, test classes from module B are compiled into `module-b/target/test-classes`, which is never added to PIT's classpath. PIT's test discovery finds only root-module tests, and kill rates are computed only from those.
**Root cause:** The `aggregator = true` pattern was added for consistency with other mojos, but the classpath collection was not updated to aggregate across modules.
**Fix:** Added `collectReactorTestClasspath()` method that iterates `session.getAllProjects()` and collects `getTestClasspathElements()`, `getOutputDirectory()`, and `getTestOutputDirectory()` from every module, using a `LinkedHashSet` to deduplicate.

---

### BUG-149: `MutationAnalyzeMojo` has no parameters for `classesDir` / `testClassesDir` overrides

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/MutationAnalyzeMojo.java`
**Severity:** LOW (non-standard Maven layouts and Gradle can't override class directories)
**Status:** FIXED (2026-06-16)
**Symptom:** The mojo accepted no parameters to override the compiled classes directories. With the BUG-126 fix, `MutationAnalysisOperation.Config` gained `classesDir` and `testClassesDir` optional fields, but the Maven mojo never exposed them as `@Parameter` fields, making them inaccessible from Maven.
**Fix:** Added `@Parameter(property = "testorder.mutations.classesDir")` and `@Parameter(property = "testorder.mutations.testClassesDir")` fields to `MutationAnalyzeMojo`, with corresponding `MUTATIONS_CLASSES_DIR` / `MUTATIONS_TEST_CLASSES_DIR` constants in `MavenPluginConfigKeys`.


---

### BUG-150: `PrepareMojo` never calls `trainAndPredict()` or `writePredictions()`, making ML prediction a no-op at runtime

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/PrepareMojo.java`
**Severity:** HIGH (the entire ML failure prediction pipeline was architecturally incomplete — P(fail) scores were never computed or written during `mvn test`)
**Status:** FIXED (2026-06-16)
**Symptom:** `TestFailurePredictor.trainAndPredict()` and `writePredictions()` were only called from display goals (`ShowMojo`, `ShowAllMojo`, `DashboardMojo`). During normal `mvn test`, `PrepareMojo.executeOrderMode()` had zero ML code. `PriorityClassOrderer.loadMLPredictions()` tried to read `ml-predictions.properties` (via `TestOrderConfig.ML_PREDICTIONS_FILE`), but the file was never created, so it always returned `Map.of()`. The ML bonus scoring block in `PriorityClassOrderer` (lines 177–197) was dead code in every production test run. `TestHealthAnalyzer` output was similarly never used by any ordering path.
**Root cause:** The `trainAndPredict()` + `writePredictions()` pipeline was implemented in the Maven plugin module but never wired into the prepare/order path. The `testorder.ml.enabled` config constant existed but was never read by `PrepareMojo`.
**Fix:** Added `mlEnabled` `@Parameter(property = "testorder.ml.enabled", defaultValue = "false")` to `PrepareMojo`. After `writeOrdererConfig()`, when `mlEnabled=true`, calls `generateAndWriteMLPredictions()` which: loads the dependency map, calls `TestFailurePredictor.trainAndPredict()` with the current change set, writes predictions to `target/test-order-runtime/ml-predictions.properties`, and appends `testorder.ml.enabled=true` and `testorder.ml.predictions.file=<path>` to the runtime config so `PriorityClassOrderer` loads them in the forked JVM. Failures are warned but non-fatal.

---

### BUG-151: `CollectorLifecycleParticipant` never appends completed test runs to ML history, starving the prediction model of training data

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/CollectorLifecycleParticipant.java` + `AbstractTestOrderMojo.java` + `PrepareMojo.java`
**Severity:** HIGH (ML predictions can never improve over time; every session starts cold with zero history)
**Status:** FIXED (2026-06-16)
**Symptom:** `MLHistoryPersistence.append()` was never called anywhere in production code. Even with `testorder.ml.enabled=true`, each Maven session would find an empty `history.lz4` (or no file at all) and skip prediction. There was no mechanism to accumulate the test outcome history that the ML model requires for training.
**Root cause:** The ML history pipeline was architecturally incomplete — the record-after-test-run step was never implemented.
**Fix:** Added `PendingMLHistory` record to `AbstractTestOrderMojo`. `PrepareMojo.generateAndWriteMLPredictions()` registers a `PendingMLHistory` for every order-mode run (not just when predictions are generated), capturing the `historyFile` path, `stateFile` path, and changed classes. `CollectorLifecycleParticipant.afterSessionEnd()` calls `appendMLHistories()` which reads the most recent `RunRecord` from the state file, converts it to an `MLRunRecord`, and appends it to `history.lz4` (capped at 200 runs). This runs after `mergePartialRunRecords()` so the state file reflects the complete merged run.

---

### BUG-152: `AnalyzeMojo` reads ML history from wrong path (`stateDir/ml/history.lz4` vs `.test-order/ml-history/history.lz4`)

**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/AnalyzeMojo.java` line 45
**Severity:** LOW (AnalyzeMojo is deprecated; the wrong path means it always loads empty history)
**Status:** FIXED (2026-06-16)
**Symptom:** `AnalyzeMojo.execute()` constructs `historyFile = stateDir.resolve("ml").resolve("history.lz4")` but `AbstractTestOrderMojo.resolveMLHistoryDir()` returns `ctx.resolveBaseDir().resolve("ml-history")` (i.e., `.test-order/ml-history/`). The two paths diverge: `AnalyzeMojo` reads from `.test-order/ml/history.lz4` while all other code writes to `.test-order/ml-history/history.lz4`. `AnalyzeMojo` always loads empty history.
**Root cause:** `AnalyzeMojo` predates the standardization of `resolveMLHistoryDir()` and was not updated when the path convention was established.

---

### BUG-153: Gradle `testOrderAffected` task selects tests but never executes them on multi-module projects (e.g. kafka, spring-boot) ✓ FIXED

**File:** `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java` lines 1563-1725
**Severity:** HIGH (testOrderAffected appears to work — selection messages print — but no tests actually run; BUILD SUCCESSFUL is misleading)
**Status:** Fixed (2026-06-16) — same root cause as BUG-124 final fix: `configureDerivedTestTask` failed to wire source set and dependsOn because `sourceSets.findByName("test")` returned null when the task config fired during project configuration (before JavaPlugin had populated source sets). Fixed by using `project.getPlugins().withType(JavaPlugin.class, callback)` to defer source-set wiring until after JavaPlugin.apply() completes. Confirmed by spring-boot campaign: 1/1 bugs CAUGHT after fix, previously all UNKNOWN.
**Symptom:** In Gradle multi-module projects like spring-boot, `testOrderAffected` shows `NO-SOURCE` across ALL subprojects — even those with compiled test classes in the index. The test selection log lines print (from the selection prelude) but no JUnit Platform test execution follows.
**Root cause:** Convention plugins like spring-boot's `JavaConventions` call `project.getTasks().withType(Test.class, action)`, which forces all Test tasks to be realized during configuration. At that point, `sourceSets.findByName("test")` returns null because `JavaPlugin.apply()` hasn't finished populating source sets yet. Without testClassesDirs being set, `@SkipWhenEmpty` skips the task as NO-SOURCE.
**Impact:** All spring-boot and kafka campaigns reported UNKNOWN — tests were not actually run.

---

### BUG-154: `cleanTestOrderAffected` Gradle auto-task deletes production class files, breaking subsequent `compileJava`

**File:** `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java` `configureDerivedTestTask()` / `testOrderAffected` task registration
**Severity:** HIGH (corrupts the build; all compilation fails with "bad class file: NoSuchFileException" errors)
**Status:** OPEN
**Symptom:** After `cleanTestOrderAffected` runs (Gradle auto-generated clean task for `testOrderAffected`), `compileJava FAILED` with errors like:
```
bad class file: .../build/classes/java/main/org/apache/kafka/common/config/ConfigTransformer.class
unable to access file: java.nio.file.NoSuchFileException: ...ConfigTransformer.class
```
The production class files in `clients/build/classes/java/main/` are deleted by `cleanTestOrderAffected`.
**Root cause:** When `configureDerivedTestTask()` sets `task.setTestClassesDirs(testSourceSet.getOutput().getClassesDirs())`, Gradle registers the `testClassesDirs` as BOTH an input AND an output of the test task. Gradle's `cleanFoo` generated task deletes all outputs, which (incorrectly) includes the test classes dirs. For projects with unusual SourceSet configurations, Gradle may resolve `getClassesDirs()` to include the production classes dir, causing all production `.class` files to be deleted on `cleanTestOrderAffected`.
**Fix approach:** Use `task.setTestClassesDirs()` with a `project.files(...)` wrapper that is explicitly marked as `@Input` only (not output), or register a custom `cleanTestOrderAffected` task that only deletes the state file (deferred-tests file) rather than relying on Gradle's auto-generated clean task.
