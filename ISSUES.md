# Codebase Issues — Systematic Review

> Generated 2026-06-17. Covers architecture, design, and code issues across all modules.
> Organized by severity: **Critical** (data corruption / wrong results) → **Major** (correctness bugs) → **Minor** (design / code quality).

---

## Critical Issues

### C1. `BytecodeHashStore`: Shared `ThreadLocal<MessageDigest>` across nested visitor classes
**File:** `test-order-core/.../changes/BytecodeHashStore.java`  
`MethodHashingVisitor` and `FieldVisitor` share the same `ThreadLocal<MessageDigest>` as the outer class. When hashing a class with multiple methods in a parallel stream, each method visitor reuses and resets the same digest instance, producing wrong per-method hashes. Methods in multi-method classes will have colliding or incorrect hashes, causing missed or spurious change detection.

### C2. `IndexCollectorServer`: Unsynchronized `HashSet` values in `ConcurrentHashMap`
**File:** `test-order-core/.../IndexCollectorServer.java`  
`mergedClassDeps` maps class names to plain `HashSet`s. The `merge` lambda calls `existing.addAll(incoming)` without synchronizing on `existing`. Concurrent handler threads writing to the same set cause lost updates or `ConcurrentModificationException`.

### C3. `OfflineInstrumentor`: `.instrumented` marker written before backup completes
**File:** `test-order-agent/.../OfflineInstrumentor.java`  
The sentinel file marking a directory as instrumented is written before all original class files are backed up. A crash mid-backup leaves the directory in an inconsistent state that subsequent runs treat as already-instrumented, skipping restoration.

### C4. `OfflineInstrumentor`: `ClassIdMap` singleton not reset between `instrument()` calls
**File:** `test-order-agent/.../OfflineInstrumentor.java`  
`instrument()` resets counters but not the `ClassIdMap` singleton. A second invocation (e.g., incremental instrumentation) produces `maxClassId=0`, corrupting the ID→class mapping for all subsequently instrumented classes.

### C5. `UsageStore`: Non-atomic dual volatile write for `activeState`/`activeClassTracker`
**File:** `test-order-agent/.../runtime/UsageStore.java`  
`activeState` and `activeClassTracker` are written in two separate volatile stores. A thread observing `activeState` non-null but `activeClassTracker` still null proceeds with a partially-visible state, producing incorrect usage records.

### C6. `PartialRunAggregator`: Delete outside lock scope
**File:** `test-order-core/.../PartialRunAggregator.java`  
`deletePartFiles()` is called after releasing the file lock. A crash between the save and delete leaves part files that will be double-merged on the next run, duplicating dependency entries.

### C7. `IndexCollectorServer.loadMapping`: Race between `classNames` and `memberNames` assignment
**File:** `test-order-core/.../IndexCollectorServer.java`  
`classNames` is assigned before `memberNames`. A handler thread observing `classNames != null` may read `memberNames == null` and NPE or produce incomplete records.

---

## Major Issues

### M1. `ChangeAnalysis`: Double SA expansion on non-degraded path
**File:** `test-order-core/.../ops/workflows/ChangeAnalysis.java`  
Static-analysis expansion runs twice when no degradation condition is triggered, inflating the changed-member set and causing more tests to run than necessary.

### M2. `ChangeAnalysis`: `bytecodeChangedMethodKeys` empty on first run
**File:** `test-order-core/.../ops/workflows/ChangeAnalysis.java`  
When `prev.isEmpty()` (first run after clean), `bytecodeChangedMethodKeys` is never populated, suppressing all first-run method-level change detection and falling back to class-level only.

### M3. `StaticCallGraphAnalyzer`: Jump/switch instructions hashed without targets
**File:** `test-order-core/.../changes/StaticCallGraphAnalyzer.java`  
`visitJumpInsn` hashes only the opcode. `visitLookupSwitchInsn` hashes keys but not labels. `visitTableSwitchInsn` hashes min/max/count but not label targets. Control-flow-only changes (reordered branches, added cases) are invisible to the call graph hash.

### M4. `StaticCallGraphAnalyzer`: Diamond-inheritance `addCallEdge` stops at first ancestor
**File:** `test-order-core/.../changes/StaticCallGraphAnalyzer.java`  
`addCallEdge` stops propagating at the first common ancestor in a diamond hierarchy, missing some call paths and producing an incomplete reverse call graph.

### M5. `StaticCallGraphAnalyzer`: Overloaded methods collapse to same callerKey
**File:** `test-order-core/.../changes/StaticCallGraphAnalyzer.java`  
Method keys don't include descriptors, so `foo(int)` and `foo(String)` share a callerKey. A change to one overload marks both as changed.

### M6. `SourceFileModel`: Text-block line tracking off-by-one
**File:** `test-order-core/.../changes/SourceFileModel.java`  
Phase 1 counts newlines; phase 2 splits on newlines. The resulting line index is off by one for any file containing text blocks, producing wrong line-level change attribution.

### M7. `SourceFileModel`: `normalizeForHashing` emits wrong triple-quote count
**File:** `test-order-core/.../changes/SourceFileModel.java`  
The text-block opening `"""` can be emitted as 2–4 quotes depending on `pendingSpace` state, producing different hashes for semantically identical text blocks.

### M8. `StructuralDiff.readFilesFromGit`: Assumes ordered git output
**File:** `test-order-core/.../changes/StructuralDiff.java`  
`git cat-file --batch` output order is assumed to match input request order. For unordered `Collection` inputs the mapping can be silently wrong, attributing one file's content to another.

### M9. `TestScorer`: Backward-compat constructor supplies `int` as `double weightedDepOverlap`
**File:** `test-order-core/.../TestScorer.java`  
Old constructors pass raw `depOverlap` (integer count) where the new API expects a normalized `double`. Any caller using the old constructor gets a score that is orders of magnitude too large.

### M10. `TestScorer`: `score()` and `explain()` arithmetic diverge on kill-rate
**File:** `test-order-core/.../TestScorer.java`  
The kill-rate formula in `score()` differs from the one in `explain()`. The displayed explanation does not match the ordering decision.

### M11. `TelemetryListener`: SIGTERM window between IO and state clear
**File:** `test-order-junit/.../TelemetryListener.java`  
`finishedNormally=true` is set after IO but before maps are cleared. A SIGTERM in this window causes a double-save on the next run, duplicating telemetry entries.

### M12. `TelemetryListener`: `executionOrder` iterated without lock
**File:** `test-order-junit/.../TelemetryListener.java`  
`buildRunRecord` iterates `executionOrder` without holding its monitor, while `testFinished` writes to it concurrently, risking `ConcurrentModificationException`.

### M13. `PriorityClassOrderer`: Wrong recovery value in catch block
**File:** `test-order-junit/.../PriorityClassOrderer.java`  
On scoring failure the catch block saves the new (partially-computed) ordering as `backup` instead of the original ordering, so recovery restores the bad state.

### M14. `PriorityMethodOrderer`: All state in static volatile fields
**File:** `test-order-junit/.../PriorityMethodOrderer.java`  
Static fields mean all test classes share one orderer instance's state. Parallel JUnit engines or multiple test suites in one JVM produce incorrect ordering.

### M15. `PriorityMethodOrderer`: Overloaded methods collide in `scoreIndexMap`
**File:** `test-order-junit/.../PriorityMethodOrderer.java`  
`scoreIndexMap` is keyed by method name only. Two `@Test void foo(TypeA)` and `@Test void foo(TypeB)` overloads share one score entry, and one silently overwrites the other.

### M16. `TddEnforcementCore`: Rename detection fires violation for nested classes
**File:** `test-order-core/.../TddEnforcementCore.java`  
The second guard in the rename-detection path always fires for nested classes regardless of whether a rename was found, emitting spurious TDD violations.

### M17. `TddEnforcementCore`: `Class.forName` uses wrong classloader
**File:** `test-order-core/.../TddEnforcementCore.java`  
`Class.forName` uses the bootstrap classloader, not the test classloader, causing `ClassNotFoundException` for any application class inspected during enforcement.

### M18. `SelectiveLearnSupport`: `null` sentinel return
**File:** `test-order-core/.../changes/SelectiveLearnSupport.java`  
Returns `null` to mean "can't compute, fall back." `null` is not distinguishable from a legitimate empty result at call sites, risking NPE or incorrect fallback decisions.

### M19. `TestSelector.selectTopN`: Premature budget exhaustion
**File:** `test-order-core/.../TestSelector.java`  
`counted` is incremented even when `LinkedHashSet.add` returns false (duplicate already present). The selection budget is exhausted before the requested N unique tests are selected.

### M20. `TieredTestSelector`: `Long.MAX_VALUE * tier2Fraction` overflows
**File:** `test-order-core/.../TieredTestSelector.java`  
When `totalDuration == Long.MAX_VALUE` (unknown tests), multiplying by `tier2Fraction` (a double < 1) overflows to a large negative long, making the tier-2 budget effectively infinite.

### M21. `LearnWorkflow`: IOException silently falls back to UNCOMMITTED
**File:** `test-order-core/.../ops/workflows/LearnWorkflow.java`  
An IOException during change-mode resolution silently selects `UNCOMMITTED` without logging, masking the error and potentially running the wrong analysis mode.

### M22. `AutoWorkflow`: Pre-aggregation can double-aggregate dependencies
**File:** `test-order-core/.../ops/workflows/AutoWorkflow.java`  
Partial-run aggregation runs before mode resolution. With `alwaysLearn=true` the aggregated deps are then merged again during learning, duplicating entries in the dependency map.

### M23. `DependencyMap.filterForModule`: Does not filter `methodDependencies`
**File:** `test-order-core/.../DependencyMap.java`  
`filterForModule` filters class-level deps but leaves `methodDependencies` unfiltered, causing cross-module method deps to survive the per-module filter.

### M24. `DependencyMap.mergeWith`: Bypasses cache invalidation
**File:** `test-order-core/.../DependencyMap.java`  
`mergeWith` assigns fields directly instead of calling `invalidateCaches()`, leaving stale cached data visible after a merge.

### M25. `PersistenceSupport.pruneLocks`: Reintroduces original race
**File:** `test-order-core/.../PersistenceSupport.java`  
`pruneLocks` checks size and removes entries in two non-atomic steps, reintroducing the check-then-act race that the lock redesign was intended to fix.

### M26. `StateSerializer`: LZ4 magic detection can misidentify data
**File:** `test-order-core/.../StateSerializer.java`  
The heuristic for detecting LZ4 vs JSON reads a magic-byte prefix that could match valid JSON starting with the same bytes, silently decoding JSON as LZ4 and producing garbage.

### M27. `StateMigrations`: Identity migration mutates potentially immutable map
**File:** `test-order-core/.../StateMigrations.java`  
The no-op migration puts a new key into the map returned by a previous migration. If that map is `Map.of()` or similar, the put throws `UnsupportedOperationException`.

### M28. `AsmClassTransformer`: Catches `Throwable` including `Error`
**File:** `test-order-agent/.../AsmClassTransformer.java`  
Catching `Throwable` swallows `OutOfMemoryError`, `StackOverflowError`, and other JVM errors, masking fatal conditions and potentially continuing with corrupted state.

### M29. `IntelligentClassFilter`: Cache slot permanently lost on duplicate
**File:** `test-order-agent/.../IntelligentClassFilter.java`  
On cache eviction of a duplicate entry the size counter is decremented even though no slot was actually freed, causing the cache to shrink permanently and eventually stop caching.

### M30. `IndexCollectorClient`: Signed short length prefix overflows for >32KB strings
**File:** `test-order-agent/.../runtime/IndexCollectorClient.java`  
`writeString` uses a signed `short` (max 32767) for the length prefix. Any string longer than 32KB is silently truncated, producing corrupt records on the server side.

### M31. `DetectDependenciesOperation`: String-scanning JSON parser
**File:** `test-order-core/.../ops/DetectDependenciesOperation.java`  
`loadKnownVictims` and `loadPriorResults` parse JSON by substring scanning. A class name that is a substring of another class name produces false matches.

### M32. `DeltaDebugging`: Initial verification run not counted against budget
**File:** `test-order-core/.../ops/detection/DeltaDebugging.java`  
`runBudget` is not decremented for the initial verification step. With a tight budget this means ddmin runs one extra test suite silently, potentially exceeding the intended limit.

### M33. `DeltaDebugging`: Budget exhaustion returns full list treated as success
**File:** `test-order-core/.../ops/detection/DeltaDebugging.java`  
On budget exhaustion the full (un-minimized) list is returned. Callers have no way to distinguish this from a successful minimization, and may report a large polluter set as confirmed.

### M34. `MLFeatureExtractor`: EWMA gives higher weight to older observations
**File:** `test-order-core/.../ml/MLFeatureExtractor.java`  
The EWMA update formula applies `(1-α)` to the new observation and `α` to the running average, the inverse of the standard definition. Older observations decay more slowly than intended.

### M35. `FailureHistoryTracker`: "Atomic snapshot" comment is wrong
**File:** `test-order-core/.../FailureHistoryTracker.java`  
Two consecutive `new HashMap<>(map)` calls are not atomic. A concurrent modification between the two copies can produce a snapshot where class-level and method-level maps are inconsistent.

### M36. `BytecodeHashStore.hashClass`: Partially-populated store causes false "all methods deleted"
**File:** `test-order-core/.../changes/BytecodeHashStore.java`  
A store containing only class hashes (no per-method hashes) is treated as "all methods existed and are now missing" on the next run, triggering spurious full re-analysis.

### M37. `MethodHashStore`: Kotlin `FooKt` class name breaks carry-over
**File:** `test-order-core/.../changes/MethodHashStore.java`  
The `classToFile` reverse index maps Java naming conventions only. Kotlin file-class names (`FooKt`) do not resolve, and their method hashes are never carried over between runs.

### M38. `CombinedAdaptiveAlgorithm`: Multi-polluter detection incomplete
**File:** `test-order-core/.../ops/detection/CombinedAdaptiveAlgorithm.java`  
Once any one polluter is confirmed, all further minimization is skipped. Tests with multiple polluters are reported with only the first discovered.

### M39. `ReactorReorderer.buildPredecessors`: NPE if project not in predecessors map
**File:** `test-order-maven-plugin/.../maven/ReactorReorderer.java`  
If a project doesn't appear in the predecessors map (e.g., has no declared deps), `buildPredecessors` dereferences a null value and throws NPE, aborting reactor reordering.

### M40. `CollectorLifecycleParticipant.drainCollectors`: Removes session key before parallel modules finish
**File:** `test-order-maven-plugin/.../maven/CollectorLifecycleParticipant.java`  
The session key is removed from the registry before all parallel modules have been drained, causing subsequent modules to find no collector and silently drop their dependencies.

### M41. `TestOrderPlugin` (Gradle): `System.setProperty` targets Gradle process, not forked JVM
**File:** `test-order-gradle-plugin/.../gradle/TestOrderPlugin.java`  
System properties set via `System.setProperty` affect only the Gradle daemon. Forked test JVMs do not inherit them, so configuration passed this way is silently ignored.

### M42. `GradleTestRunner.writeInitScript`: Not synchronized for parallel builds
**File:** `test-order-gradle-plugin/.../gradle/GradleTestRunner.java`  
Parallel Gradle subproject configurations can call `writeInitScript` concurrently, overwriting the same init script file mid-write and producing a truncated or corrupt script.

---

## Minor Issues (Design / Code Quality)

### D1. `TestOrderState`: TOML sentinel `-1` reused for "unset"
**File:** `test-order-core/.../TestOrderState.java`  
`-1` is used as both a valid config value and the sentinel for "not yet set." A config explicitly set to `-1` is indistinguishable from unset.

### D2. `TestOrderState`: Floating-point `!=` comparison for config persistence
**File:** `test-order-core/.../TestOrderState.java`  
Config values are compared with `!=` to decide whether to persist. Floating-point representation can cause equal configs to differ by a ULP, triggering unnecessary saves.

### D3. `TestOrderState.pruneDeletedTestClasses`: Dead `retained.add` code
**File:** `test-order-core/.../TestOrderState.java`  
`retained.add(...)` is called but `retained` is never used to filter the result, making the retention logic a no-op.

### D4. `STATE_LOAD_CACHE`: Stores live mutable objects
**File:** `test-order-core/.../TestOrderState.java`  
The cache holds references to live `TestOrderState` instances. Modifications by one caller are visible to others retrieving the "cached" state, violating cache semantics.

### D5. `DurationTracker`: `Math.min(measuredMs, Long.MAX_VALUE)` is dead code
**File:** `test-order-core/.../DurationTracker.java`  
`Math.min(x, Long.MAX_VALUE)` always returns `x`. The clamp is a no-op and signals confused intent.

### D6. `DurationTracker`: Plain `LinkedHashMap` not thread-safe
**File:** `test-order-core/.../DurationTracker.java`  
`durations` is a plain `LinkedHashMap` accessed from the test-execution thread and the scoring thread without synchronization.

### D7. `RunHistoryManager`: `thinRunHistory` balance is off for small `maxRuns`
**File:** `test-order-core/.../RunHistoryManager.java`  
For `maxRuns=3` the policy keeps 1 recent + 2 historical, giving historical runs 2× the weight of recent runs. The split should be symmetric or documented.

### D8. `APFDCalculator`: Returns 1.0 for no-failure runs
**File:** `test-order-core/.../APFDCalculator.java`  
A run with no failures receives APFD=1.0, which is treated by the optimizer as a perfect score. This pollutes the optimization history with uninformative data points.

### D9. `ScoringOptimizer`: `IntegerChromosome.of(-1, -1)` on invalid sentinel
**File:** `test-order-core/.../ScoringOptimizer.java`  
If weight range sentinels aren't validated, the chromosome is constructed with `min == max == -1`, which jenetics may reject or treat as a degenerate population.

### D10. `ChangeAnalysis.joinOrRethrow`: Hides `Error` behind `CompletionException`
**File:** `test-order-core/.../ops/workflows/ChangeAnalysis.java`  
`CompletionException` wraps arbitrary `Throwable`. Callers catching `CompletionException` silently swallow `OutOfMemoryError` and other non-exceptions.

### D11. `FileHashStore`: No explicit charset in load/save
**File:** `test-order-core/.../changes/FileHashStore.java`  
Platform-default charset is used. On Windows with a non-UTF-8 locale, paths containing non-ASCII characters are corrupted on read or write.

### D12. `FileHashStore`: Save normalizes `\\` to `/`, load does not
**File:** `test-order-core/.../changes/FileHashStore.java`  
Path separators are normalized on save but not validated on load. A store written on one platform may not find its entries on another if the normalization is not symmetric.

### D13. `SourceFileModel`: `removeCommentsAndEmptyLines` can read past end on bad `\` escape
**File:** `test-order-core/.../changes/SourceFileModel.java`  
The string-literal handler doesn't bounds-check before reading the character after `\`, causing an `IndexOutOfBoundsException` on malformed source with a trailing backslash.

### D14. `BytecodeDependencyAugmenter`: Inverted graph rebuilt on every call
**File:** `test-order-core/.../changes/BytecodeDependencyAugmenter.java`  
The reverse-dependency graph is reconstructed from scratch on each augmentation call. For large dependency maps this is an avoidable O(n) cost per invocation.

### D15. `TelemetryPersistence`: Class-level EMA updated N times per run
**File:** `test-order-core/.../TelemetryPersistence.java`  
Each method result updates the class-level EMA independently. For a class with N methods the class-level EMA moves N times instead of once, over-weighting the most recent run.

### D16. `TelemetryPersistence.emergencySave`: Silent exception swallow
**File:** `test-order-core/.../TelemetryPersistence.java`  
`emergencySave` catches all `Exception` without logging or re-throwing. A corrupted state file on an emergency save leaves no trace.

### D17. `StateRecordCodec`: Implicit wire-format positions
**File:** `test-order-core/.../StateRecordCodec.java`  
Array positions in the serialized format are implicit positional integers with no named constants or documentation. Adding a field in the middle silently shifts all subsequent fields.

### D18. `PartialRunAggregator`: Space delimiter with no escaping
**File:** `test-order-core/.../PartialRunAggregator.java`  
The `.part` file format uses space as a delimiter. Test class names containing spaces (legal in JUnit 5 display names) break parsing.

### D19. `LZ4Support.frameOutputStream`: Uses FAST not MEDIUM compression
**File:** `test-order-core/.../LZ4Support.java`  
`frameOutputStream(OutputStream)` uses `FAST` compression while the enum default is `MEDIUM`. Callers expecting consistent compression levels across overloads get different behavior.

### D20. `PriorityClassOrderer`: `loadMLPredictions` called on every `orderClasses`
**File:** `test-order-junit/.../PriorityClassOrderer.java`  
ML prediction files are re-read from disk on every ordering invocation with no caching. For large test suites this adds repeated I/O overhead.

### D21. `FixedOrderSupport.resolveOrderFilePath`: Re-reads properties on every call
**File:** `test-order-junit/.../FixedOrderSupport.java`  
The properties file is reloaded on every call. The result should be cached after the first successful load.

### D22. `FixedOrderSupport.applyOrder`: Clears list before safe copy
**File:** `test-order-junit/.../FixedOrderSupport.java`  
`descriptors` is cleared and then repopulated. If repopulation throws, the list is left empty with no recovery path.

### D23. `ClassOrderingEngine`: `LOGGED_STATE_ERRORS` bound is soft
**File:** `test-order-core/.../ClassOrderingEngine.java`  
The check-then-add on `LOGGED_STATE_ERRORS` is not atomic. Under parallel test execution the same error can be logged more than the intended cap.

### D24. `ClassOrderingEngine`: Two nearly-identical `orderByScoreAndDiversity` implementations
**File:** `test-order-core/.../ClassOrderingEngine.java`  
One operates on `String`, the other on generic `T`. They share no code, meaning bug fixes must be applied twice.

### D25. `MethodOrderingEngine`: Sub-millisecond durations truncated to 0
**File:** `test-order-core/.../MethodOrderingEngine.java`  
`(long)getDurationMethod(...)` discards fractional milliseconds, grouping all sub-millisecond methods together and breaking tie-breaking for fast test suites.

### D26. `MethodOrderingEngine.orderByScoreAndDiversity`: Non-deterministic tie-breaking
**File:** `test-order-core/.../MethodOrderingEngine.java`  
When scores are equal, ordering depends on `HashMap.keySet()` iteration order, which is non-deterministic across JVM runs, making test ordering irreproducible.

### D27. `SetCoverComputer`: Shallow defensive copy in constructor
**File:** `test-order-core/.../SetCoverComputer.java`  
The constructor copies the outer map but not the `Set` values. Callers that retain a reference to the original sets can mutate the computer's internal state.

### D28. `SetCoverComputer`: O(N×U) lazy-recheck is unbounded
**File:** `test-order-core/.../SetCoverComputer.java`  
The greedy set-cover loop rechecks all remaining sets after each selection. For N tests and U unique deps this is O(N×U) per selection, or O(N²×U) total.

### D29. `TestSplitAdvisor.cluster`: Early exit at 50 not globally optimal
**File:** `test-order-core/.../TestSplitAdvisor.java`  
The greedy clustering exits after 50 inner iterations. The comment claims O(n³) complexity but the algorithm can exceed this with the early exit, and the result is not guaranteed optimal.

### D30. `AbstractTestOrderMojo.createMinimalMavenProject`: Reflection on private field
**File:** `test-order-maven-plugin/.../maven/AbstractTestOrderMojo.java`  
A private Maven `MavenProject` field is set via reflection. This breaks with Maven versions that restrict reflective access and is fragile against internal API changes.

### D31. `AbstractTestOrderMojo.injectTestClasspath`: Does not merge `additionalClasspathElements`
**File:** `test-order-maven-plugin/.../maven/AbstractTestOrderMojo.java`  
When the Surefire property `additionalClasspathElements` already exists, the new entries overwrite rather than merge, silently removing user-configured classpath additions.

### D32. `SurefireHelper`: Reads `System.getProperty("parallel")` instead of Maven properties
**File:** `test-order-maven-plugin/.../maven/SurefireHelper.java`  
The parallel-execution detection reads a JVM system property instead of the Maven user properties map, failing to detect parallelism configured via `mvn -Dparallel=...`.

### D33. `ReactorContext.moduleId()`: Dash separator can collide
**File:** `test-order-maven-plugin/.../maven/ReactorContext.java`  
Module IDs are constructed as `groupId-artifactId`. A group `com.example-foo` with artifact `bar` produces the same ID as group `com.example` with artifact `foo-bar`.

### D34. `RuntimeRealmInjector`: `world.getRealms()` iterated without synchronization
**File:** `test-order-maven-plugin/.../maven/RuntimeRealmInjector.java`  
The realm list is iterated without holding any lock. A concurrent classloader operation during parallel builds can cause `ConcurrentModificationException`.

### D35. `Agent.agentArgs`: Comma split with no escaping
**File:** `test-order-agent/.../Agent.java`  
Agent arguments are split on `,` with no support for escaping. A file path containing a comma silently truncates the argument, causing silent misconfiguration.

### D36. `Agent`: `JarFile` opened but never closed
**File:** `test-order-agent/.../Agent.java`  
`appendRuntimeJarToBootstrap` opens a `JarFile` and never closes it, leaking a file descriptor for the lifetime of the JVM.

### D37. `Agent`: TOCTOU race in cache validation
**File:** `test-order-agent/.../Agent.java`  
`Files.size(cachedJar) > 0` checks the file, then the file is used. Between check and use the file could be replaced or truncated.

### D38. `CiDepDownloadManager`: GitHub token loaded regardless of provider
**File:** `test-order-ci/.../CiDepDownloadManager.java`  
The GitHub token is read from the environment unconditionally, even when using a non-GitHub CI provider, potentially logging a warning about a missing token unnecessarily.

### D39. `GitHubActionsDownloader.fetchJson`: Unbounded response body
**File:** `test-order-ci/.../GitHubActionsDownloader.java`  
The HTTP response body is read into memory without a size cap. A malicious or misconfigured server can cause an OOM by returning an arbitrarily large response.

### D40. `GitHubActionsDownloader`: OkHttpClient created per instance
**File:** `test-order-ci/.../GitHubActionsDownloader.java`  
Each downloader instance creates its own `OkHttpClient` with its own thread pool and connection pool. In a multi-module build this creates N redundant clients.

### D41. `CombinedAdaptiveAlgorithm`: `runsUsed` always adds 15 for ddmin
**File:** `test-order-core/.../ops/detection/CombinedAdaptiveAlgorithm.java`  
The run budget tracking hard-codes 15 for the ddmin phase regardless of how many test-runner invocations actually occurred, making the budget accounting inaccurate.

### D42. `ConflictGraph.edgesFor()`: Returns mutable internal list
**File:** `test-order-core/.../ops/detection/ConflictGraph.java`  
Callers receive a direct reference to the internal `ArrayList`. Any caller that modifies it silently corrupts the graph.

### D43. `TestRunner.predecessorsOf`: O(n) indexOf lookup
**File:** `test-order-core/.../ops/detection/TestRunner.java`  
`predecessorsOf` uses `List.indexOf` on every call. In an O(n²) loop this makes the overall algorithm O(n³).

### D44. `TestHealthAnalyzer`: Only negative autocorrelation triggers FLAKY
**File:** `test-order-core/.../ml/TestHealthAnalyzer.java`  
The flakiness detector flags tests with alternating pass/fail (negative autocorrelation) but not tests with clustered failures (positive autocorrelation), missing a common flakiness pattern.

### D45. `TestHealthAnalyzer.computeVolatility`: Biased population variance
**File:** `test-order-core/.../ml/TestHealthAnalyzer.java`  
Divides by N instead of N-1, producing a biased (downward) variance estimate for small sample sizes, making recent-failure volatility appear lower than it is.

### D46. `AffectedOperation.scoredCount` can go negative
**File:** `test-order-core/.../ops/AffectedOperation.java`  
`scoredCount` is decremented when a test falls into multiple categories, potentially producing a negative displayed count in the selection summary.

### D47. `ChangeDetectionOps`: Asymmetric IOException handling
**File:** `test-order-core/.../ops/ChangeDetectionOps.java`  
On IOException, `detectChangedClasses` returns `Set.of()` (empty = "no changes") while `detectChangedTestClasses` returns `"explicit"` mode. The asymmetry means the same underlying failure produces different downstream effects.

### D48. `ChangeDetectionOps.snapshotMethodHashesSingleRoot`: No atomic rename
**File:** `test-order-core/.../ops/ChangeDetectionOps.java`  
Other persistence operations use write-to-temp + atomic rename. This method writes directly, leaving a window where a crash produces a partial hash file.

### D49. `LearnWorkflow`: `--enable-native-access=ALL-UNNAMED` hard-coded
**File:** `test-order-core/.../ops/workflows/LearnWorkflow.java`  
This JVM flag does not exist on JDK 8 or 11, causing the JVM invocation to fail on older runtimes with no useful error message.

### D50. `PendingRunCoordinator`: `ConcurrentHashMap` unnecessary
**File:** `test-order-core/.../PendingRunCoordinator.java`  
The map is always accessed under `synchronized`, so `ConcurrentHashMap` provides no benefit and adds misleading implied concurrency semantics.

### D51. `SelectionSummary.format()`: Defaults to Maven commands for Gradle projects
**File:** `test-order-core/.../ops/AffectedOperation.java`  
The formatted selection summary always shows `mvn` commands even when the project uses Gradle, producing confusing output for Gradle users.

### D52. `TestOrderPlugin` (Gradle): `doLast` not run on task failure
**File:** `test-order-gradle-plugin/.../gradle/TestOrderPlugin.java`  
The `doLast` action that restores instrumented classes to their originals is not registered as a finalizer. If the test task fails, classes are left in their instrumented state and not restored.

---

## Architecture / Design Issues

### A1. Protocol version proliferation in `IndexCollectorClient`
**File:** `test-order-agent/.../runtime/IndexCollectorClient.java`  
Versions V1–V4 encode two orthogonal feature flags (binary payload and member deps) as a linear version sequence. This forces unnecessary upgrades and makes the version space hard to reason about. A capability-bitfield approach would be cleaner.

### A2. `snapshotHashes` duplicated between `LearnWorkflow` and `AutoWorkflow`
**Files:** `test-order-core/.../ops/workflows/LearnWorkflow.java`, `AutoWorkflow.java`  
The same hash-snapshotting logic is copy-pasted with divergent feature sets. Changes to one are not automatically reflected in the other.

### A3. `TestOrderState.STATE_LOAD_CACHE` caches mutable live objects
**File:** `test-order-core/.../TestOrderState.java`  
A cache of mutable `TestOrderState` objects violates the cache contract: entries are not immutable, and the cache does not know when they've been modified. This is effectively a global mutable singleton under the guise of a cache.

### A4. Static fields in `PendingRunCoordinator` shared across parallel Maven modules
**File:** `test-order-core/.../PendingRunCoordinator.java`  
Parallel Maven builds in the same JVM share `PendingRunCoordinator` static state. Module A's pending-run records can bleed into Module B's decisions.

### A5. `DependencyMap`: Non-deterministic serialization due to `HashMap`
**File:** `test-order-core/.../DependencyMap.java`  
`dependencies` is a `HashMap`. Serialized output is non-deterministic, making binary-format diffs and cache keys unreliable. Other hash stores (e.g., `MethodHashStore`) use `TreeMap` — this should too.

### A6. `MethodScorer` and `TestScorer` set-cover logic diverges
**Files:** `test-order-core/.../TestScorer.java`, `MethodScorer.java`  
The floor value for set-cover bonus is `0.1` (double) in one and `1` (int) in the other. The semantics of set-cover bonus skip for zero-count entries also differ. There is no shared implementation.

### A7. Fragile `isProjectTargeted` prefix matching in Gradle plugin
**File:** `test-order-gradle-plugin/.../gradle/TestOrderPlugin.java`  
Project inclusion/exclusion uses simple string prefix matching, which can match unintended projects if one project path is a prefix of another (e.g., `:foo` matches `:foo-bar`).

### A8. `MLFeatureExtractor` and `TestHealthAnalyzer` duplicate `TestStats` ring-buffer
**Files:** `test-order-core/.../ml/MLFeatureExtractor.java`, `TestHealthAnalyzer.java`  
Both files define their own `TestStats`/`TestSequence` ring-buffer implementation with the same fields. There is no shared base class or utility.

### A9. JSON parsing via string scanning in `DetectDependenciesOperation`
**File:** `test-order-core/.../ops/DetectDependenciesOperation.java`  
Using substring search to parse JSON is fundamentally brittle and will produce false matches when class names share prefixes. The project already uses JSON libraries; this should use them.

### A10. `ChangeAnalysis.moduleFilter` silently discards filtered result
**File:** `test-order-core/.../ops/workflows/ChangeAnalysis.java`  
When `filteredResult.size() == beforeCount`, the filter concludes "nothing was filtered" and discards the result entirely rather than returning the (valid) filtered set. The size comparison is not a reliable filter-effectiveness test.

---

*Total: 7 Critical, 44 Major, 52 Minor/Design, 10 Architecture — 113 issues identified.*

---

## Pass 2 — Edge cases in recent commits (2026-06-18)

> Targeted re-audit of post-19ca2f88 changes: ubiquitous-dep compression (2d14f31d, fe7df1df),
> state facade extractions (12ae53b4, 135dd414, 791b661c), and the BUG-160-165 fixes.

### P2-C1. `DependencyMap.getAffectedTests`: ubiquitous-dep short-circuit returns wrong test set
**File:** `test-order-core/.../DependencyMap.java:728-734`
`if (ubiquitousDeps.contains(changed)) return new LinkedHashSet<>(dependencies.keySet());` returns **all tests**, but a "ubiquitous" dep is one present in `≥ threshold` (default 0.9) of tests — up to 10% of tests genuinely don't have it. When a near-ubiquitous dep changes, all tests are reported affected, including the 10% that don't depend on it. Cascades into:
- `AffectedOperation.java:134` matchCount inflated → bad prioritization
- `Tool.java:181, 339` and `ChangeAnalysis.java:449` over-select tests
- `OrderWorkflow.java:55` `boosted` set inflated, score boosting applied to tests that don't actually depend on the change
Severity: correctness bug for ranking signals; safe (no missed tests) for `:affected` selection. Fix: use `ubiqAbsent` data (already loaded) to compute the real per-dep test set instead of short-circuiting.

### P2-M1. `DependencyMap` binary read assumes section ordering with no validation
**File:** `test-order-core/.../DependencyMap.java:1465-1477, 1638-1664`
DEP_GROUPS reader captures `ubiqPresenceByDep` (set by SECTION_UBIQUITOUS_DEPS) at line 1476. The current writer always emits section 9 before DEP_GROUPS, so the assumption holds today, but the read loop iterates sections in file order with no order check. A reordered file (future writer change, third-party tool, or hand-crafted) silently falls through to the `!hasUbiq` branch and produces test dep sets missing all ubiquitous entries — silent corruption with no exception. Fix: either two-pass read (collect all sections first, then resolve), or document and assert the ordering invariant when reading.

### P2-M2. `DependencyMap` ubiquitous-dep read silently drops corrupt entries
**File:** `test-order-core/.../DependencyMap.java:1658`
`if (depId >= 0 && depId < finalTrie.size())` — out-of-range depId is silently skipped after the absent bitmap was already consumed. The bitmap's test-index entries are also not validated against `testNames.length` (only DEP_GROUPS' member bitmap validates indices, lines 1504-1507). A malformed or cross-version index can produce `IndexOutOfBoundsException` later in `ubiqAbsent.contains(ti)` calls, with no clear pointer to the corruption. Fix: throw `IOException` on out-of-range IDs in section 9; validate absent-bitmap indices against test count.

### P2-M3. `TestMetricsTracker.pruneDeletedTestClasses`: zombie inner-class entries when outer survives
**File:** `test-order-core/.../TestMetricsTracker.java:139-167`
The outer-class scan at line 139 skips entries containing `$` (line 140), so inner classes are never independently checked for existence. The follow-up loop at line 154 only adds an inner class to `pruned` when its outer is also being pruned. Result: if you delete a `@Nested` test class (`Outer$Inner.class`) but keep `Outer.class`, the `Outer$Inner` entry is **never** pruned — it accumulates in `durationTracker` and `failureHistory` indefinitely. Fix: when scanning, also resolve `Outer$Inner` to `Outer$Inner.class` and prune individually if absent.

### P2-M4. `RunHistoryStorage.setHistoryMaxRuns`: violates `@ThreadSafe` annotation
**File:** `test-order-core/.../RunHistoryStorage.java:58-61`
The class is annotated `@ThreadSafe`, but `setHistoryMaxRuns` is not `synchronized`:
1. Line 60 (`runHistory.trimToMax`) races with the `synchronized addRunRecord` on the underlying list.
2. Lines 59-60 (config update + trim) are not atomic — a concurrent `addRunRecord` can read the old `historyMaxRuns()` between them and add beyond the new cap.
Fix: `synchronized void setHistoryMaxRuns(int maxRuns)` to match the rest of the class's locking discipline.

### P2-D1. `TestMetricsTracker.pruneDeletedTestClasses`: redundant set rebuilds
**File:** `test-order-core/.../TestMetricsTracker.java:149-164`
Builds `retained`, `allTracked`, then `finalRetained` — three nearly-identical sets — when one mutable set updated in place would suffice. Minor inefficiency on every prune; no correctness impact but ugly enough to invite future bugs.

---

*Pass 2 totals: 1 Critical (P2-C1), 4 Major (P2-M1–4), 1 Minor (P2-D1) — 6 new issues.*
