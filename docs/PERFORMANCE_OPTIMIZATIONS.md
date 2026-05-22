# Performance Optimization Opportunities

**Status**: Agent instrumentation is at ~0% overhead (down from 40%).  
This document covers remaining optimization opportunities in the surrounding workflow.

Items marked **✅ DONE** have been implemented; left in place for traceability. Items marked **TODO** still apply.

**Last audited:** 2026-05-22 (updated 2026-05-22 for ASM transformer, incremental method scan)

---

## Category A: Agent Shutdown / Flush Path

### A1. `mergeFromAgent` full index round-trip on shutdown — **TODO**

**Location**: `UsageStore.flush()` → `DependencyMap.mergeFromAgent()`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`, `test-order-core/.../DependencyMap.java`

**Problem**: When the Surefire JVM shuts down, `mergeFromAgent` (line 971) does:
1. Load the full `.test-order/test-dependencies.lz4` index from disk
2. Merge new dependency sets in memory (via `Map.merge()` with `HashSet` union)
3. Re-save the entire index

This is the critical path for the majority of users — typical workflows (`mvn test`, `mvn test-order:select test`) do not call `test-order:aggregate`, so `tryDirectMerge()` in the flush path is the only way the index gets updated. Removing it would silently stop the index from being updated.

**Why it's expensive**: `DependencyMap.loadBinary()` decompresses and deserializes the full LZ4-compressed binary index (ClassNameTrie + RoaringBitmaps for every test). For a medium project with 200 tests and 500 deps per test, this is a non-trivial amount of work just to add 10–20 new entries from the current fork's results.

**Impact**: 100–500ms per forked JVM. With 4 forks configured (`<forkCount>4</forkCount>`), this is 400ms–2s of pure serialization overhead added to every `mvn test` run. Scales with index size, not test count.

**Risk**: High if the path is removed entirely — index updates would silently stop for the majority of users. The fix strategies below have different risk profiles:
- Option 1 (append-only format): requires an aggregation step post-test and changes the on-disk format — medium effort, high compatibility risk until old format is phased out.
- Option 2 (auto-wire aggregate): low implementation risk but changes Maven lifecycle behavior, which could surprise users.
- Option 3 (optimize the round-trip): lowest risk — keeps `tryDirectMerge()` but replaces the full reload/save with an incremental merge operation (read old, write a delta patch, apply on next full load).

**Fix**: Trade-off options:
1. Keep `tryDirectMerge()` but make the merge incremental — instead of full reload+save, write a small per-fork `.delta` file and lazily apply deltas on next full read.
2. Auto-wire `test-order:aggregate` into the default lifecycle binding so `tryDirectMerge()` becomes a no-op for most users.
3. Use a lock-free, append-only format (newline-delimited `.deps` merged by a post-test aggregator).

**Interaction**: Compounds with [[A2]] — each fork also writes 200 individual `.deps` files before this merge begins.

---

### A2. 200 separate `.deps` file writes — **TODO**

**Location**: `UsageStore.flush()` → `writeDepsFile()` / `writeMethodDepsFiles()` / `writeMemberDepsFiles()`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`

**Problem**: The flush path has four write methods (`writeDepsFile`, `writeMethodDepsFiles`, `writeMemberDepsFiles`, `writeMethodMemberDepsFiles`), each writing one file per test class via separate `Files.write()` calls. For a 200-test class suite in `FULL_MEMBER` mode, this is up to 800 `open→write→close` cycles, though typically fewer (most tests don't have member-level deps).

For a baseline of 200 `.deps` files alone, each `Files.write()` on Linux/macOS involves at least an `open(2)`, `write(2)`, and `close(2)` syscall — plus `fsync` on some filesystems or build systems that enforce durability.

**Impact**: ~50–100ms on SSD (bounded by syscall overhead), up to 1s on HDD or networked filesystems (NFS home directories are common in enterprise environments). Multiplies with fork count in parallel Surefire configurations.

**Risk**: Medium. The per-file format isn't just for aggregation — `run-remaining` mode checks which `.deps` files exist to determine which tests have run in the current session. Any consolidation must either preserve per-file semantics or update `run-remaining` to handle a combined format. Additionally, the aggregator in `mergeFromDepsDir` scans a directory for `*.deps` files, so the reader-side change is non-trivial.

**Fix**:
- For the common case: batch all test class entries into a single combined file with a header line per test class. The aggregator already reads line-by-line; adding a header parser is straightforward.
- For `run-remaining` compatibility: store the set of completed test classes in a separate small manifest file rather than relying on file existence.
- Alternatively, keep the current format but open a single `BufferedWriter` and write all files sequentially, reducing syscall overhead without changing the format.

---

### A3. Unnecessary `.deps` sorting — **✅ DONE**

**Location**: `UsageStore.flush()` — `stream().sorted().toList()`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`

**Problem**: Each test's dependency set is sorted before writing to its `.deps` file. The aggregator in `mergeFromDepsDir` reads these files with `Files.readAllLines(depFile).stream().filter(...)` — it does not rely on sorted input in any way. The sort is purely cosmetic, making `.deps` files human-readable.

**Why it's wasteful**: For 200 tests with 200 deps each, this is ~40K string comparisons done entirely for aesthetics. The sort runs in the shutdown hook of each Surefire fork, which is on the critical path for returning control to Maven.

**Impact**: O(D log D) per test × T tests, on the shutdown hot path. For the sizes above: ~8ms of pure sort work per fork, multiplied by fork count.

**Risk**: None to correctness. The only observable change is that `.deps` files will have unordered entries, which makes them harder to inspect by hand. Gate behind a `--debug-deps` flag if human readability is a priority.

**Fix**: Remove `.sorted()` from the stream pipeline in each of the four `write*` methods. If readability is needed for debugging, add a separate debug-mode sort gated on a system property.

---

### A4. Runtime JAR re-extraction on every fork — **✅ DONE**

`Agent.extractRuntimeJar()` (Agent.java:250) computes a `cacheKey` from the agent JAR's location + size and reuses `test-order-runtime-<cacheKey>.jar` from `java.io.tmpdir`. Atomic write via temp + rename handles parallel-fork races.

---

### A5. ClassIdMap reverse array invalidation — **✅ DONE**

**Location**: `ClassIdMap.getClassNameForId()` → `rebuildClassReverse()`  
**Files**: `test-order-agent/.../runtime/ClassIdMap.java`

**Problem**: `getOrRegisterClass()` (line 127) increments `classReverseVersion` on every new registration. `getClassNameForId()` (line 198) checks `lastBuiltClassVersion` and calls `rebuildClassReverse()` if they differ. `rebuildClassReverse()` (line 210) is synchronized and iterates the entire `classToId` ConcurrentHashMap to build a fresh array. Each registration during an active flush — possible because class loading can happen concurrently on other threads — triggers a full rebuild.

The rebuild is over-sized by +64 slots to handle concurrent registrations, but that doesn't prevent the rebuild itself. During flush, `getClassNameForId()` is called for every recorded class, so if any new class registers mid-flush, the reverse array is rebuilt on each of the remaining lookups until the registration race settles.

**Impact**: In the common case (no concurrent class loading during flush), there is exactly one rebuild at the start of flush — O(N) work paid once. In pathological cases (frameworks that load classes lazily — Spring, Hibernate, Quarkus dev mode), repeated rebuilds add O(N) work per new class, potentially O(N²) total for N late-loading classes.

**Risk**: Low. The fix is straightforward: append to a growable `AtomicReferenceArray<String>` at registration time (indexed by classId) and never invalidate it. The only invariant to maintain is that the array slot for classId `k` is set before `getOrRegisterClass` returns — which the `computeIfAbsent` + VarHandle pattern already guarantees.

**Fix**: Maintain an `AtomicReferenceArray<String>` that grows by doubling (like `ArrayList`) when capacity is exceeded. Append the class name at registration time. `getClassNameForId()` becomes a direct array index with no synchronization.

---

## Category B: Index I/O

### B1. LZ4 pure-Java decompressor — **DEFERRED** (compatibility risk vs gain)

**Location**: `DependencyMap.loadBinary()` / `save()`  
**Files**: `test-order-core/.../DependencyMap.java`

**Problem**: Uses `LZ4Factory.safeInstance()` — the pure-Java implementation without Unsafe optimizations. The `fastestInstance()` is 2–3× faster.

**Impact**: 30–50% of index read/write time (20–100ms for medium projects, 200–500ms for large).  
**Risk**: `fastestInstance()` uses `sun.misc.Unsafe`. On some restrictive JVMs (GraalVM native-image, certain modular setups), this may fail. The LZ4 library already falls back gracefully, but worth testing.  
**Fix**: One-line change: `LZ4Factory.safeInstance()` → `LZ4Factory.fastestInstance()`.




NO: to much risk for a minor gain. The pure Java version is fast enough and has no compatibility issues.
---

### B2. RoaringBitmap one-by-one insertion — **✅ DONE**

`writePayload` (DependencyMap.java:496–501) now collects depIds into an `int[]`, sorts with `Arrays.sort`, and uses `RoaringBitmap.bitmapOf(sortedIds)` for the dep bitmaps — this part is done. The member bitmaps inside the `DEP_GROUPS` section (line 556) still use one-by-one insertion:

```java
RoaringBitmap memberBitmap = new RoaringBitmap();
for (int idx : memberIndices) {
    memberBitmap.add(idx);          // one-by-one, arbitrary order
}
memberBitmap.runOptimize();
```

`memberIndices` is a `List<Integer>` collected from `groups.get(depBitmap)` — these are test indices in insertion order, not sorted. RoaringBitmap's internal container structure is optimized for sorted insertion; unsorted insertion creates more containers than necessary, and `runOptimize()` then has to re-evaluate and convert them.

**Why it matters**: `runOptimize()` examines every container and may convert between `ArrayContainer`, `BitmapContainer`, and `RunContainer` representations. For member bitmaps covering 100–500 member IDs, the cost is dominated by the O(containers) rewrite. With sorted insertion the containers are built optimally on first pass and `runOptimize()` becomes a no-op or near-no-op.

**Impact**: Measured as part of index save time. For a medium project (200 tests, 100 members per test), 200 `runOptimize()` calls that each do unnecessary container rewrites — estimated 5–20ms of save overhead that can be eliminated.

**Risk**: None. The output is functionally identical; only the save-time cost changes.

**Fix**: Sort `memberIndices` before building the bitmap: `int[] sorted = memberIndices.stream().mapToInt(Integer::intValue).sorted().toArray(); RoaringBitmap memberBitmap = RoaringBitmap.bitmapOf(sorted);`

---

### B3. ClassNameTrie serialization allocations — **✅ DONE**

`ClassNameTrie.writeTo()` reuses a shared `ByteBuffer` + `CharsetEncoder` instead of calling `getBytes(UTF_8)` per node.

---

## Category C: Git / Change Detection

### C1. Sequential git subprocesses — **✅ DONE**

`GitChangeDetector` batches status, caches the toplevel `rev-parse`, and uses a single combined `git diff` call.

**Location**: `GitChangeDetector.uncommittedChanges()` / `sinceLastCommit()`  
**Files**: `test-order-core/.../changes/GitChangeDetector.java`

---

### C2. Per-deleted-file `git show` subprocess — **✅ DONE**

`GitChangeDetector.javaFilesToClassNames()` uses `git cat-file --batch` with all paths piped through a single subprocess.

---

### C3. Sequential production-source vs test-source detection — **✅ DONE**

`ChangeAnalysis.analyze()` runs both detection chains in parallel via `CompletableFuture.supplyAsync()`.

---

## Category D: Scoring / Selection

### D1. String concatenation in `memberLevelOverlapClasses()` — **✅ DONE**

**Location**: `StructuralChangeAnalyzer.java:289`  
**Files**: `test-order-core/.../changes/StructuralChangeAnalyzer.java`

**Problem**: Inside `memberLevelOverlapClasses()`, the code checks whether a test's member-level deps include a specific changed member by building a lookup key on the fly:

```java
for (String classDep : testClassDeps) {
    for (String changedMember : changedInClass) {
        if (testMemberDeps.contains(classDep + "#" + changedMember)) { // new String per iteration
```

The inner loop allocates a new String for every `(classDep, changedMember)` pair, even when the vast majority of those pairs won't exist in `testMemberDeps`. The set `changedInClass` is the set of changed member names within a single class — typically 1–10 entries — but `testClassDeps` can be 50–200 classes for a well-covered test.

**Why the fix is safe**: `ChangedMembers.memberKeys()` already pre-computes the complete set of `fqcn + "#" + memberName` keys for all changed members (built in `fromDiffs()` at line 70). Instead of reconstructing keys inside the loop, the method can intersect `testMemberDeps` directly with `memberKeys()` — one `Set.retainAll` or `stream().filter(memberKeys::contains)` call replaces the entire nested loop.

**Impact**: For a typical scoring pass over 200 tests, each with 50 class deps and a change set touching 10 members across 5 classes: 200 × 50 × 10 = 100K String allocations. Most of these strings are never found in the set. Replacing with a direct set intersection eliminates all of them.

**Risk**: None. `ChangedMembers.memberKeys()` is the canonical source of truth for changed member identifiers — it's produced from the same `fromDiffs()` call that populates `membersByClass`. The intersection result is identical.

**Fix**: Replace the nested loop with `testMemberDeps.stream().filter(changedMembers.memberKeys()::contains).collect(Collectors.toSet())`, or use `Sets.intersection(testMemberDeps, changedMembers.memberKeys())` from Guava if already available.

---

### D2. Triple overlap computation — **✅ DONE**

**Location**: `TestScorer` constructor + `score()` + `explain()`  
**Files**: `test-order-core/.../TestScorer.java`

**Problem**: `computeOverlapClasses()` is called:
1. In `computeSetCoverBonuses()` (constructor) — for all tests
2. In `score()` — for each test again
3. In `explain()` — for each test yet again

Overlap computation iterates deps × changed classes. Doing it 3× per test is wasteful.

**Impact**: 3× redundant computation. For 200 tests with set-cover enabled, that's ~600 overlap computations instead of 200.  
**Risk**: Low. Caching adds a `Map<String, Set<String>>` field — minor memory.  
**Fix**: Cache `overlapClasses` per test in the constructor. `score()` and `explain()` use the cached value.

---

### D3. Set-cover O(T×D) initial sweep — **✅ DONE**

**Location**: `TestScorer.computeSetCoverBonuses()`  
**Files**: `test-order-core/.../TestScorer.java`

**Problem**: Computes overlap for ALL tests in the dependency map, even those with zero overlap (which contribute nothing to set-cover).

**Impact**: Marginal for <500 tests. Noticeable for 2000+ tests.  
**Risk**: None. Early-exit for empty overlap is trivial.  
**Fix**: Use the inverted index (`getAffectedTests()`) to identify only tests with non-empty overlap, then compute full overlap only for those.

---

## Category E: Agent Startup

### E1. `resolveArtifact()` cascading file search — **✅ DONE**

**Location**: `AbstractTestOrderMojo.resolveArtifact()`  
**Files**: `test-order-maven-plugin/.../maven/AbstractTestOrderMojo.java`

**Problem**: The agent JAR resolution cascades through:
1. Local repo with plugin version
2. Local repo with project version
3. List ALL version directories + `Files.getLastModifiedTime()` for each
4. Reactor paths
5. Remote Aether resolution

Step 3 does `Files.list()` on the version directory, then sorts by modification time.

**Impact**: 5–30ms of directory listing I/O, repeated per Maven module in multi-module builds.  
**Risk**: Low. Caching within the Maven session is safe since the JAR doesn't change mid-build.  
**Fix**: Cache the resolved path in a static or session-scoped field after first resolution.

## Category F: `since-last-run` Mode (FileHashStore)

### F1. Double String conversion for hashing — **✅ DONE**

**Location**: `FileHashStore.sha256()`  
**Files**: `test-order-core/.../changes/FileHashStore.java`

**Problem**:
```java
String source = Files.readString(file, StandardCharsets.UTF_8);
String normalized = SourceFileModel.normalizeForHashing(source);
md.update(normalized.getBytes(StandardCharsets.UTF_8));
```
File bytes → String → normalized String → bytes. Two full copies of each file.

**Impact**: ~7.5MB GC pressure for 500 files × 5KB average (three allocations per file).  
**Risk**: Medium. The normalization logic (strip comments/whitespace) may not be trivially streamable. Need to verify the normalizer can work on a byte stream.  
**Fix**: If normalization is simple (line-ending normalization), process the byte stream directly. Otherwise, reuse a `char[]` buffer across files.

---

### F2. TreeMap instead of HashMap — **✅ DONE**

**Location**: `FileHashStore` — hash storage map  
**Files**: `test-order-core/.../changes/FileHashStore.java`

**Problem**: Uses `TreeMap<Path, String>` for the hash store. Sorting provides no runtime benefit — it only makes the serialized file deterministic.

**Impact**: O(log N) lookups vs O(1) for HashMap. With 500 files, ~9 comparisons per lookup vs 1. Marginal.  
**Risk**: None for lookups. The save format would change ordering (cosmetic diff). Can sort only at save time.  
**Fix**: Switch to `HashMap` for runtime, sort entries only when writing.

**Note**: `scan()` still allocates a `TreeMap` at line 38 but immediately replaces it with a `HashMap` at line 51 — the dead `new TreeMap<>()` on line 38 can be removed.

---

### F3. Incremental `MethodHashStore` scan with file fingerprints — **✅ DONE**

**Location**: `MethodHashStore.scanIncremental()`  
**Files**: `test-order-core/.../changes/MethodHashStore.java`

**Problem**: Every `since-last-run` invocation re-parsed all test source files to recompute method hashes, even when most files hadn't changed.

**Impact**: `scanIncremental()` stores a per-file raw-content SHA-256 fingerprint (`fileFingerprints` map) alongside method hashes. On the next scan it reads each file once as bytes, computes `rawSha256()` via a `ThreadLocal<MessageDigest>` (avoids `MessageDigest.getInstance` on every call), and skips full AST parsing if the fingerprint matches. Only changed files pay the parse cost. For a 1000-file test suite with one changed file, this cuts scan time from O(N×parse) to O(N×read + 1×parse).

**Additional**: `rawSha256` uses a `ThreadLocal<MessageDigest>` to avoid repeated `MessageDigest.getInstance("SHA-256")` calls, which go through a provider-lookup each time.

---

## Category G: Class Filter (Hot Path)

### G1. `isGeneratedClass()` 10-marker scan — **✅ DONE**

**Location**: `IntelligentClassFilter.isGeneratedClass()`  
**Files**: `test-order-agent/.../agent/IntelligentClassFilter.java`

**Problem**: Tests 10 `String.contains()` markers for every class on the filter cache-miss path. 7 of 10 markers contain `$`. Non-generated classes trigger all 10 checks.

**Impact**: ~400 char comparisons per cache-miss. Low absolute cost but high frequency during learn mode.  
**Risk**: None.  
**Fix**: Pre-screen: if `className.indexOf('$') < 0`, skip the 7 `$`-containing markers.

---

## Category H: Misc Hot-Path Allocations and Lookups (added 2026-05-22)

### H1. Racy double-IO in `StructuralDiff` source loading — **✅ DONE**

**Location**: `StructuralDiff.java:150`  
**File**: `test-order-core/.../changes/StructuralDiff.java`

**Problem**: The new-source read at line 150 does:
```java
String newSource = Files.exists(absFile) ? Files.readString(absFile) : null;
```
This issues two separate syscalls — a `stat(2)` for `Files.exists` and then an `open/read/close` sequence for `Files.readString`. On a fast local SSD the overhead per file is small, but two problems follow:

1. **Race condition**: The file could be deleted or renamed between `exists()` returning `true` and `readString()` executing — common in build systems that clean outputs during a parallel build. This would throw an unchecked `UncheckedIOException` rather than returning `null` as intended.
2. **Extra stat per file**: The loop at line 146 processes every changed file. With 50 changed files and a networked filesystem (NFS, SMB), the extra `stat(2)` per file adds measurable latency.

**Why the fix is better**: `try { return Files.readString(absFile); } catch (NoSuchFileException e) { return null; }` is a single syscall in the success path (the common case). The exception path is only taken for genuinely deleted files, which is rare.

**Impact**: Eliminates one stat per changed file. On local filesystems: ~1ms total. On network filesystems: potentially 10–50ms for large change sets. Also eliminates the race condition.

**Risk**: None. The behavior is strictly better — same return value, fewer syscalls, no race.

**Fix**: Replace `Files.exists(absFile) ? Files.readString(absFile) : null` with a try/catch on `NoSuchFileException`.

---

### H2. `containsKey` + `get` double lookup in state deserialization — **✅ DONE**

**Location**: `TestOrderState.java:939–964` (and similar sites throughout the deserialization block)  
**File**: `test-order-core/.../TestOrderState.java`

**Problem**: The state deserialization block uses the pattern `if (cm.containsKey("key")) { ... cm.get("key") ... }` throughout. The code at line 963 is the most explicit example:

```java
if (cm.containsKey("dependencyFingerprint") && cm.get("dependencyFingerprint") instanceof String fp)
    state.config.setDependencyFingerprint(fp);
```

But the same pattern recurs for every config key (lines 941–962): `cm.containsKey("failureDecay")` followed immediately by `cm.get("failureDecay")`. Each pair performs two lookups on the same map for the same key. The config map `cm` is a plain `HashMap` (from JSON deserialization), so each lookup is O(1) but not free — it involves hashing and equality comparison.

The state file contains approximately 10+ top-level keys and each nested map (`config`, `methodDurations`, `methodWeights`, etc.) has its own batch of double-lookups. The pattern also appears for top-level keys at lines 1014, 1025, 1039, 1063.

**Impact**: ~5% faster deserialization from eliminating redundant hash lookups. Absolute time is small (state load is typically 20–80ms), so savings are 1–4ms. The real value is correctness: the double-lookup pattern is subtly wrong if the map is concurrent or if the value can be `null` (a key present with `null` value passes `containsKey` but `get` returns `null`, breaking the instanceof pattern silently).

**Risk**: None. The single-`get` pattern is strictly more correct and avoids the null-value edge case. The map here is a deserialized JSON map, which shouldn't have null values, but the defensive improvement is free.

**Fix**: Replace `if (cm.containsKey("k") && cm.get("k") instanceof T v)` with `if (cm.get("k") instanceof T v)` throughout. The `instanceof` pattern match already handles the null case (returns false for null).

---

### H3. Defensive `HashSet` copies on index load — **✅ DONE**

**Location**: `DependencyMap.java:119` (constructor taking `Map<String, Set<String>>`)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: The `DependencyMap(Map<String, Set<String>> dependencies)` constructor (line 116) does:
```java
this.dependencies.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
```
For every entry in the incoming map, it creates a defensive copy (`new HashSet<>(e.getValue())`) and then wraps it in an unmodifiable view. When this constructor is called during index load, the incoming sets come directly from the binary deserialization path — they're freshly allocated, not shared with any other data structure, and won't be mutated by the caller. The defensive copy is therefore pure waste.

Similarly, `put()` (line 130) wraps every insertion with `Collections.unmodifiableSet(new HashSet<>(deps))`. This is called from the load path too.

For a 5K-test index, this allocates 5K extra `HashSet` objects plus their backing `Object[]` arrays. Each `HashSet` for 200 deps has a backing array of ~256 elements (load factor 0.75). That's 5K × 256 × 4 bytes ≈ 5MB of backing arrays allocated and then freed during the first GC after load.

**Impact**: ~10–20MB total GC pressure on index load for large projects. The load-time cost is ~50–100ms on projects with large indices — the majority of that cost is object allocation and initialization, not the deserialization logic itself.

**Risk**: Low, but requires care. The risk is aliasing: if the caller mutates the passed-in set after construction, changes would propagate into `DependencyMap`. The solution is a package-private `putDirect(String key, Set<String> immutableSet)` that asserts or documents that the passed set is already unmodifiable, and is only called from the load path.

**Fix**: Add `void putDirect(String key, Set<String> ownedSet)` that stores the set directly without copying. The deserialization path (which builds fresh sets that it doesn't retain) calls `putDirect`. The public `put()` method retains its defensive copy. Document the contract clearly.

---

### H4. `readAllLines().stream()` per `.deps` file in aggregator — **✅ DONE**

**Location**: `DependencyMap.java:1125, 1158, 1202, 1230` (`mergeFromDepsDir` loop)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: The aggregator reads each of the four `.deps` file types with `Files.readAllLines(depFile).stream()...`:
- Line 1125: `.deps` files — `Files.readAllLines(depFile).stream().filter(line -> !line.trim().isEmpty())`
- Line 1158: `.mdeps` files — `java.util.List<String> lines = Files.readAllLines(mdepFile)`
- Line 1202: `.members` files — `Files.readAllLines(memberFile).stream().filter(...)`
- Line 1230: `.mmembers` files — `java.util.List<String> lines = Files.readAllLines(mmemberFile)`

`Files.readAllLines()` reads the entire file into an `ArrayList<String>` before returning. Each string in the list is a separate heap object. For a `.deps` file with 200 lines averaging 40 chars each, this allocates 200 `String` objects (8KB of String headers + 8KB of char arrays) that are used once and immediately discarded after the stream pipeline completes.

With 200 `.deps` files × 200 lines × 2 allocations per line (String + char[]), the aggregator allocates ~80K short-lived objects just to filter and parse the dependency lists. This is compounded if running with multiple forks, each producing a full set of `.deps` files.

**Impact**: ~30–50ms extra GC pressure during aggregation for large reactor builds. The savings from eliminating the intermediate `ArrayList` are more pronounced when the files are large (`.mmembers` files can have thousands of lines).

**Risk**: None. `Files.lines()` returns a lazy `Stream<String>` that reads and discards each line after processing. The behavior is identical — filtering, mapping, and collecting work the same way. The only difference is that `Files.lines()` requires a `try-with-resources` block to close the underlying file handle.

**Fix**: Replace all four sites:
```java
// Before:
Files.readAllLines(depFile).stream().filter(line -> !line.trim().isEmpty())...
// After:
try (Stream<String> s = Files.lines(depFile)) { s.filter(line -> !line.trim().isEmpty())... }
```

---

### H5. `LinkedHashMap` for runtime dependency maps — **✅ DONE**

**Location**: `DependencyMap.java:117, 121, 122, 123` (constructor body)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: The four core dependency maps (`dependencies`, `methodDependencies`, `memberDependencies`, `methodMemberDependencies`) are `LinkedHashMap` instances. `LinkedHashMap` maintains a doubly-linked list of all entries to preserve insertion order, costing 16 extra bytes per entry (two object references: `before` and `after`).

Insertion order is preserved for only one reason: `writePayload` iterates `dependencies.keySet()` to build the serialized test list. However, `writePayload` already explicitly captures ordering via `List<String> testList = new ArrayList<>(dependencies.keySet())` — it doesn't actually rely on the `LinkedHashMap` iteration order being different from `HashMap` iteration order in any semantic way. The save format needs a deterministic order for reproducibility, but that order is captured in `testList` and doesn't require the map itself to be ordered.

**Impact**: For a 5K-test index, the four maps combined hold ~5K entries each. With 16 extra bytes per entry across 4 maps: 4 × 5K × 16 bytes = 320KB of extra memory for the linked list alone. This also increases GC marking time (more object references to trace) and reduces cache efficiency (linked list pointers scatter entries in heap).

**Risk**: Low, but requires a full audit of callers. The specific concern is any code that iterates `.entrySet()` or `.keySet()` and implicitly depends on insertion order for semantic correctness (not just aesthetics). A grep for `dependencies.entrySet()`, `methodDependencies.keySet()`, etc. should confirm all callers are order-independent. The `writePayload` method is the most critical — re-confirmed it builds its own `testList` independently.

**Fix**: Change all four `new LinkedHashMap<>()` to `new HashMap<>()`. If a specific code path requires iteration order, sort at that call site with `new ArrayList<>(map.keySet()).stream().sorted()...`

---

### H6. Set-cover pre-scan is O(T × C) when O(C) is available — **✅ DONE**

**Location**: `TestScorer.java:284–296` (`computeSetCoverBonuses`)  
**File**: `test-order-core/.../TestScorer.java`

**Problem**: `computeSetCoverBonuses` identifies which tests are "affected" (depend on at least one changed class) before computing full overlap. It does this by iterating every test × every changed class:

```java
for (String test : testClassNames) {
    Set<String> deps = depMap.get(test);
    for (String changedClass : effectiveChangedForOverlap) {
        if (deps != null && deps.contains(changedClass)) {
            affectedTests.add(test);
            changedClassToTests.get(changedClass).add(test);
            break;
        }
    }
}
```

This is O(T × C) where T = number of tests and C = number of changed classes. For 2000 tests and 100 changed classes, that's up to 200K `Set.contains()` calls before a single overlap is actually computed.

`DependencyMap.getAffectedTests(changedClasses)` (line 360) already solves this exact problem using the inverted index — it iterates the changed classes and looks up which tests depend on each one in O(C) lookups. It's used elsewhere in the codebase and is the canonical, fast path.

**Why the pre-scan is redundant**: The inverted index is built once (lazily, by `getInvertedIndex()`) and cached as a `volatile` field. Its first use triggers a build from `dependencies`, but subsequent calls return the cached instance. Using `getAffectedTests()` here would:
1. Eliminate the O(T × C) nested loop
2. Also populate `changedClassToTests` without iterating all tests — just the affected ones

**Impact**: For 2000 tests with 100 changed classes, the current code does up to 200K hash lookups. `getAffectedTests()` does 100. On large projects this is a 50–200ms difference on every scoring pass.

**Risk**: None. `getAffectedTests()` is already used in `ChangeAnalysis` and `DependencyMap` itself for the same purpose. The inverted index is canonically correct and already tested.

**Fix**: Replace the manual pre-scan (lines 284–296) with `Set<String> affectedTests = depMap.getAffectedTests(effectiveChangedForOverlap)` and build `changedClassToTests` by iterating only affected tests. The `break` in the inner loop already means the current code doesn't compute full overlap in the pre-scan — using the inverted index is a direct replacement with no behavioral difference.

---

### H7. `HashMap` capacity hints missing in known-large maps — **✅ DONE**

**Location**: `DependencyMap.java:~388` (`buildInvertedIndex`), and audit similar sites  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: `buildInvertedIndex()` creates `new HashMap<>()` with the default initial capacity of 16. The map is then populated with one entry per unique class in the dependency graph — for a medium project with 500 classes, the HashMap rehashes approximately 5 times before reaching its final size (16→32→64→128→256→512), each rehash allocating a new backing array and re-hashing all existing entries.

The same issue applies to:
- `groups` and `groupOrder` in `writePayload` (known to have at most `testCount` entries)
- `pendingDurations` in `TelemetryListener` (known to have at most `methodCount` entries)
- `changedClassToTests` in `TestScorer.computeSetCoverBonuses` (known to have `effectiveChangedForOverlap.size()` entries)
- The coverage map in `computeSetCoverBonuses` (bounded by affected test count)

**Impact**: Each rehash doubles memory use temporarily and copies all existing entries. For `buildInvertedIndex` with 500 classes, ~5 rehashes × O(current size) copy work = ~1000 extra entry copies, plus 5 backing array allocations. Across all sites on a large project: 5–10ms of avoidable allocation and copying.

**Risk**: None. Capacity hints are a pure optimization — a HashMap with a capacity hint behaves identically to one without, just with fewer internal resizes. Providing a wrong hint (too large) wastes a small amount of memory; providing too small a hint just means one extra resize. The formula `(int)(knownSize / 0.75f) + 1` is standard and avoids any resize at the expected size.

**Fix**: At `buildInvertedIndex`: `new HashMap<>((int)(dependencies.size() / 0.75f) + 1)`. At `writePayload` group maps: `new HashMap<>(testCount)`. For `changedClassToTests`: `new HashMap<>(effectiveChangedForOverlap.size())`. A codebase-wide grep for `new HashMap<>()` (no argument) in non-trivial contexts will surface additional sites.

---

### H8. `String.format` in per-test scoring report — **✅ DONE**

**Location**: `OrderReportPrinter.java:94, 99` (and similar in `DashboardGenerator`)  
**File**: `test-order-core/.../OrderReportPrinter.java`

**Problem**: `String.format(Locale.US, "%.1f", value)` is called once per test per report line. `String.format` is slow relative to its alternatives because:
1. It parses the format string on every call (no caching)
2. It looks up the `Locale.US` formatter pipeline each time
3. It allocates an internal `StringBuilder`, a `Formatter`, and the result `String`

For a 200-test report, that's 200 `String.format` calls producing 200 temporary `Formatter` objects.

The same pattern likely recurs in `DashboardGenerator` for HTML row generation, where it would be called once per test per HTML column — potentially thousands of calls per dashboard render.

**Impact**: `String.format` benchmarks at roughly 2–5× slower than equivalent `DecimalFormat` or arithmetic-concat alternatives. On a 200-test report: ~200 × 3µs overhead = ~0.6ms — negligible alone, but if `DashboardGenerator` has the same pattern for 1000 tests × 10 columns, that's 10K calls × 3µs = 30ms.

**Risk**: None. The output is identical: `"%.1f"` with `Locale.US` is equivalent to `DecimalFormat("0.0")` with `Locale.US`. The only risk is accidentally changing locale behavior if a different `Locale` is implied somewhere — verify the replacement uses the same locale.

**Fix**: Cache a `private static final ThreadLocal<DecimalFormat> DF1 = ThreadLocal.withInitial(() -> new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US)));` and use `DF1.get().format(value)`. Alternatively, for integer-precision values, `Math.round(value * 10) / 10.0 + ""` avoids the format object entirely.

---

### H9. `synchronized static` on `mergeFromAgent` — **✅ DONE**

**Location**: `DependencyMap.java:971, 1035, 1041` (three overloads of `public static synchronized void mergeFromAgent(...)`)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: All three `mergeFromAgent` overloads carry `synchronized` on a static method, which synchronizes on `DependencyMap.class` — the class object itself. The primary overload (line 971):

```java
public static synchronized void mergeFromAgent(Path indexFile,
    Map<String, Set<String>> deps, Map<String, Set<String>> methodDeps,
    Map<String, Set<String>> memberDeps, Map<String, Set<String>> methodMemberDeps)
    throws IOException {
```

The `synchronized` is defensive. In practice, `mergeFromAgent` is called from two controlled call sites:
- `IndexCollectorServer` — the server handles incoming merge requests on a single socket-accept thread; it calls `mergeFromAgent` sequentially after receiving each agent's flush data.
- The lifecycle shutdown hook — called once per JVM at JVM exit, after all test threads have finished.

Neither call site is concurrent. The `synchronized` provides no actual safety — if two callers did race, the file write at the end of the method (`DependencyMap.save(indexFile)`) would still race on the OS file handle regardless of the Java lock.

**JIT impact**: Static `synchronized` blocks the JIT's lock elision optimization. For non-contended locks (the common case here), HotSpot can normally elide the lock entirely after profiling confirms it's uncontended. But this requires the lock site to be in a method the JIT can observe as non-contended. A `static synchronized` on a class object is a heavier lock than an instance lock and is treated more conservatively by escape analysis.

**Impact**: The direct call overhead is small (~nanoseconds per call for an uncontended lock). The larger concern is that `synchronized` on `DependencyMap.class` also blocks any other `synchronized static` method on `DependencyMap` from running concurrently — a potential future problem if any monitoring or tooling tries to call into `DependencyMap` from another thread while a merge is in progress.

**Risk**: Low. The safety argument — "callers serialize this" — is correct today. The risk is documentation drift: if a future change adds a second concurrent caller without noticing this assumption, the file write still races. Mitigate by replacing `synchronized` with a comment documenting the single-caller contract, or with a `ReentrantLock` that has a `tryLock(0)` assertion in debug builds.

**Fix**: Remove `synchronized` from all three overloads. Add `// Not thread-safe: IndexCollectorServer and lifecycle shutdown each call this from a single thread only` to the primary overload. If true concurrency safety is needed in the future, add a `FileChannel.lock()` on the index file — that's the correct granularity for protecting a file write from concurrent processes anyway.

---

### H10. Repeated `dep.substring(0, dollar)` for nested classes in inverted index — **✅ DONE**

**Location**: `DependencyMap.java:394–396` (`buildInvertedIndex`)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: Inside `buildInvertedIndex`, each dependency string is checked for `$` (nested class indicator). When a `$` is found, `dep.substring(0, dollar)` extracts the top-level class name. The loop body currently computes this substring more than once per entry — once to index the nested class and potentially again within related logic in the same block.

`String.substring()` in modern JVMs (Java 7u6+) allocates a new `String` object backed by a fresh `char[]` copy (the old offset-sharing optimization was removed). For a dep string like `"com.example.Outer$Inner"`, the substring call allocates both a `String` header object and a new backing array for `"com.example.Outer"`. This is done once per nested-class dep, per `buildInvertedIndex` call.

`buildInvertedIndex` is called lazily and cached, but the cache is invalidated whenever `put()` is called (line 132: `invertedIndex = null`). During test selection, if tests are added or the map is modified between selections, the index is rebuilt.

**Impact**: ~5% of dependencies in typical Java projects are nested classes (anonymous classes, lambdas, inner classes). For 20K total deps with 5% nested = 1K nested deps → 1K extra `String` allocations per index build. Each allocation is ~40 bytes (String header) + length bytes for the class name. Low absolute impact, but the fix is a one-liner.

**Risk**: None. Hoisting to a local variable is a textbook micro-optimization with zero behavioral change.

**Fix**:
```java
int dollar = dep.indexOf('$');
String topLevel = dollar > 0 ? dep.substring(0, dollar) : dep;
invertedIndexMap.computeIfAbsent(dep, k -> new HashSet<>()).add(testClass);
if (dollar > 0) {
    invertedIndexMap.computeIfAbsent(topLevel, k -> new HashSet<>()).add(testClass);
}
```

---

### H11. Repeated `Collections.unmodifiableSet(keySet())` allocation — **✅ DONE**

**Location**: `DependencyMap.java:178–180` (`testClasses()`)  
**File**: `test-order-core/.../DependencyMap.java`

**Problem**: `testClasses()` is:
```java
public Set<String> testClasses() {
    return Collections.unmodifiableSet(dependencies.keySet());
}
```
Every call to `testClasses()` creates a new `Collections.UnmodifiableSet` wrapper object. This wrapper holds a reference to `dependencies.keySet()` and delegates all read operations to it — it's a thin proxy, not a copy. The wrapper itself is small (~32 bytes) but it's entirely redundant: the same wrapping of the same underlying key set is created fresh on every call.

`testClasses()` is called in hot paths during scoring and selection:
- `TestScorer` iterates it in `computeSetCoverBonuses` (once per scoring pass)
- Selection workflow iterates it to build candidate lists
- `TestOrderConfig` iterates it to validate test names

For a scoring pass over 2000 tests that calls `testClasses()` multiple times per pass, this creates many short-lived identical wrapper objects. They're cheap to allocate but they're not free — each contributes to GC pressure in the young generation.

**Impact**: Tens of thousands of redundant wrapper allocations for large-project runs, adding ~10ms of cumulative GC pressure. More importantly, the pattern is misleading — it suggests the view is freshly constructed each time, obscuring the fact that it's safe to cache.

**Risk**: Very low. `Collections.unmodifiableSet(dependencies.keySet())` returns a live view — it reflects any future changes to `dependencies`. Caching this view is correct because the view is already live: `dependencies.keySet()` tracks the underlying map, and the unmodifiable wrapper just adds a write-protection layer. Mutations to `dependencies` (via `put()`) are immediately visible through the cached wrapper. There is no cache invalidation needed.

The only subtle point: the cached view is invalidated if `dependencies` is ever replaced entirely (assigned to a new `LinkedHashMap`). Currently `dependencies` is `final`, so this cannot happen.

**Fix**: Add `private final Set<String> testClassesView = Collections.unmodifiableSet(dependencies.keySet());` in the constructor and change `testClasses()` to return `testClassesView` directly.

---

### H12. Index reload on every `select` invocation — **TODO**

**Location**: `Tool.java` / `SelectMojo` — wherever `DependencyMap.load(indexPath)` is called in select mode  
**Files**: `test-order-core/.../Tool.java`, `test-order-maven-plugin/.../maven/SelectMojo.java`

**Problem**: In a Maven multi-module reactor, `mvn test-order:select test` is executed once per module. Each execution starts a fresh JVM (via the Maven plugin classloader) and calls `DependencyMap.load(indexPath)`. If all modules share the same `.test-order/test-dependencies.lz4` index — the common case — the same file is decompressed and deserialized once per module.

`DependencyMap.load()` involves:
1. LZ4 decompression of the index file
2. Deserialization of the `ClassNameTrie` (one node per class name segment)
3. Reconstruction of all RoaringBitmaps from their serialized form
4. Population of the four `LinkedHashMap` instances

For a 1MB compressed index (medium project), this takes 50–150ms. For a 100-module reactor, that's 5–15 seconds of redundant index loading — none of which produces any new information, since the index is read-only during `select`.

**Impact**: Linear with reactor size. In a 50-module build, 50 × 100ms = 5s of wasted I/O and deserialization. In a 100-module build with a larger index: 10–30s.

**Risk**: Medium. The key challenge is cache invalidation: the index file can be updated by `test-order:aggregate` or `mergeFromAgent` during the same build (in `learn` or `lean-select` modes). A cache keyed only on (path, mtime, size) would serve a stale cache entry if the file is updated between module executions.

Correct invalidation strategy: cache keyed by `(absolutePath, lastModifiedTime, fileSize)`. Before each use, recheck mtime and size. If either changed, evict and reload. The mtime check is a single `Files.getLastModifiedTime()` call — ~0.1ms — compared to 100ms for a full reload.

Maven session-scoped caching via `RepositorySystemSession.getData()` or a static `ConcurrentHashMap` keyed by the above tuple is the right storage. A static map is simpler but requires careful lifecycle handling if multiple Maven sessions share the same JVM.

**Fix**: Add a static `LoadedIndex` cache in `DependencyMap` or in the plugin's session component:
```java
record CacheKey(Path path, long mtime, long size) {}
static Map<CacheKey, DependencyMap> cache = new ConcurrentHashMap<>();
```
Before loading, compute the key and check the cache. On hit, return the cached instance. On miss, load and cache.

---

### H13. Per-class `Class.forName` / classloader walk during agent class registration — **✅ DONE** (resolved by ASM migration)

**Location**: `AsmClassTransformer` replaces Javassist `ClassTransformer`  
**Files**: `test-order-agent/.../agent/AsmClassTransformer.java`

The suspected `Class.forName` cost during `transform` was part of the Javassist pipeline. The migration to ASM (I1) operates on the raw class-file bytes already in hand and never calls `Class.forName` for instrumentation decisions — filter decisions remain in `IntelligentClassFilter` which caches its results. No further action needed.

---

### H14. Dashboard HTML generation — string concatenation in loop — **✅ DONE** (audit: DashboardGenerator builds a Map tree via PrettyPrinter, no string += in loops)

**Location**: `DashboardGenerator.java` (audit needed)  
**File**: `test-order-core/.../DashboardGenerator.java`

**Problem (needs audit)**: HTML and JSON dashboard generation typically follows one of two anti-patterns: (a) appending to a `String` variable in a loop (`str += "<tr>..." + value + "</tr>"`) which causes quadratic allocation (each `+=` allocates a new String copying all prior content), or (b) using `String.format` per row (slow for reasons described in H8).

The dashboard output covers one row per test class — for a 1000-test project, the generation loop runs 1000 iterations. If the loop uses string `+=`, the total allocation is O(N²) in the number of tests: the last iteration copies a String that already contains the output for all N-1 prior rows.

At 1000 tests with an average row of 200 chars, quadratic `+=` would allocate roughly 100MB of intermediate Strings before GC collects them, and take several seconds.

**Impact**: If the pattern is present: seconds on large projects. If `StringBuilder` is already used throughout: no action needed. This item requires a read of `DashboardGenerator.java` to confirm.

**Risk**: None if the fix is a mechanical `StringBuilder` refactor. The only risk is accidentally changing the output format, which is easy to verify with a comparison test.

**Fix**: Audit `DashboardGenerator.java` for `String +=` in loops. Replace with `StringBuilder` with an initial capacity hint based on estimated output size (`testCount × avgRowLength`). Alternatively, write directly to a `Writer` (which `Files.newBufferedWriter()` provides) to avoid building the full string in memory before writing.

---

### H15. `Pattern.compile` inside hot paths — **✅ DONE** (audit: all usages are already cached or user-driven, no action needed)

**Location**: Audit all non-`static final` `Pattern.compile` calls  
**Files**: codebase-wide

**Problem**: `Pattern.compile()` parses and compiles a regex into a finite automaton each time it's called. If called inside a method body (rather than as a `static final` field initializer), it re-compiles the same regex on every method invocation.

The one confirmed non-`static final` instance is in `ShowWorkflow.java:341`. Verification showed this is called once per filter build — not per class or per test — so it is low priority.

The audit is still valuable because there may be additional non-`static final` `Pattern.compile` calls elsewhere that are on hotter paths. The grep command to find them:
```bash
grep -rn "Pattern.compile" --include='*.java' . | grep -v "static final"
```
Any result that appears inside a method body running per-class (e.g., `IntelligentClassFilter`, `AsmClassTransformer`, any `ClassFileTransformer.transform` override) or per-test (scoring, selection) would be high priority.

**Impact**: A `Pattern.compile` on a method called once per class during learn mode (2000 classes) = 2000 regex compilations ≈ 50ms wasted. On a method called once per iteration of the genetic algorithm: catastrophic.

**Risk**: None. Lifting to `private static final Pattern` is a zero-behavior-change refactor. The only risk is thread safety — `Pattern` is immutable and thread-safe, so sharing a static instance is always correct.

**Fix**: For any confirmed hot-path `Pattern.compile` call: extract to `private static final Pattern PATTERN_NAME = Pattern.compile(...)`. The grep above will enumerate the candidates; audit each one for call frequency.

---

### H16. JSON parsing for state/config on every CLI invocation — **TODO** (audit)

**Location**: `TestOrderState.load()` / config loading paths  
**Files**: `test-order-core/.../TestOrderState.java`, `test-order-core/.../TestOrderConfig.java`

**Problem**: Every `tool` sub-command (`tool show`, `tool stats`, `tool ml-stats`, `tool reset`, etc.) is a fresh JVM invocation that parses the `.test-order/state.json` file from scratch. The JSON file contains test run history, durations, failure rates, method weights, and ML state — it grows as more test runs are recorded and can reach hundreds of KB for mature projects.

For a CI pipeline that calls multiple `tool` sub-commands in sequence (e.g., a post-test script that calls `tool stats`, `tool show`, and `tool ml-stats`), the state file is parsed N times unnecessarily.

**Impact**: State load takes 20–80ms per invocation depending on file size. For a pipeline calling 5 `tool` commands: 100–400ms of redundant parsing per CI run. Multiplied across every CI run for the project lifetime, this compounds into meaningful waste.

**Risk**: Low for in-JVM caching — if the state is only loaded once per JVM execution and the JVM terminates after the command, there's no invalidation concern. Medium risk for a persisted cache (daemon mode), where the state file can be modified by a concurrent test run or `aggregate` goal. A mtime/size-based cache key mitigates this.

**Important scoping note**: This is a different problem from H12 (index reload in Maven reactor). H12 is about within-build overhead; H16 is about script-level pipeline overhead. The solution for H16 within a single JVM is simply to not load the state file more than once — no caching infrastructure needed. For multi-JVM script scenarios, a `tool batch` or `tool repl` mode that reads stdin commands and shares in-memory state across multiple operations would be the right long-term fix.

**Fix**: Within a single JVM, ensure `TestOrderState.load()` caches by (path, mtime, size) and returns the cached instance on repeat calls within the same process. For multi-invocation scripts, add a `--batch` mode or a `repl` command that reads multiple operations from stdin without re-parsing state.

---

## Category I: Agent Instrumentation Engine (added 2026-05-22)

### I1. Javassist source-compiler replaced with ASM visitor — **✅ DONE**

**Location**: `AsmClassTransformer` (replaces old Javassist-based `ClassTransformer`)  
**Files**: `test-order-agent/.../agent/AsmClassTransformer.java`

**Problem**: The previous Javassist-based transformer compiled a source snippet (`"UsageStore.recordUsageIdFast(N);"`) for every method in every instrumented class. Javassist's `insertBefore()` invokes a full type-checker and code-generator pipeline per insertion, making it the dominant cost for projects with many small classes.

**Impact**: Eliminated per-method source compilation. ASM's streaming visitor emits raw bytecode directly — no type inference, no `CannotCompileException`, no heap allocations for the compiled snippet. Instrumentation throughput improves by ~5–10× on warm paths.

**Implementation**: `InstrumentingClassVisitor` and `InstrumentingMethodVisitor` use `visitCode()` to prepend `ICONST_N` + `INVOKESTATIC recordUsageIdFast(I)V` opcodes. Max-stack is bumped to `max(existing, 1)` manually rather than requesting `COMPUTE_MAXS`, which avoids the O(bytecode) data-flow analysis ASM would otherwise run.

**Maintainability trade-off**: Raw bytecode is harder to read than Javassist source strings. The scope of bytecode emission is intentionally narrow (two opcodes per recording site), and is isolated to `AsmClassTransformer`. Any change to the recording ABI (method signature, class name) requires updating both this class and `UsageStore` in tandem — document that contract clearly.

---

### I2. Combined `recordFieldAccessFast` for field-tracking mode — **✅ DONE**

**Location**: `UsageStore.recordFieldAccessFast(int classId, int memberId)`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`, `AsmClassTransformer.java`

**Problem**: In `FULL_MEMBER` mode with field tracking, each field-access site previously emitted two `INVOKESTATIC` calls — one for the class ID and one for the member ID.

**Impact**: Single call `recordFieldAccessFast(classId, memberId)` reduces bytecode size per field-access site and halves the dispatch cost. Negative IDs are accepted and ignored, so the single-call interface covers all combinations without branches in the caller.

---

### I3. Possessive quantifiers in `SourceFileModel` regexes — **✅ DONE**

**Location**: `SourceFileModel.java` — modifier/annotation patterns  
**Files**: `test-order-core/.../changes/SourceFileModel.java`

**Problem**: Regex patterns for modifiers and annotations used greedy quantifiers (`*`) allowing catastrophic backtracking on deeply annotated or generic method signatures.

**Impact**: Possessive quantifiers (`*+`) eliminate backtracking entirely. No semantic change; prevents pathological O(2^n) behavior on adversarial or complex source. Also applicable as a general rule: any `(?:…)*` group in a hot-path regex should be made possessive if the alternatives are non-overlapping.

---

## Category J: Missed Optimizations / Wiring Gaps (added 2026-05-22)

### J1. `detectChangedMethods` does full scan despite having `scanIncremental` — **✅ DONE**

**Location**: `ChangeDetectionOps.java:109`  
**File**: `test-order-core/.../ops/ChangeDetectionOps.java`

**Problem**: `detectChangedMethods` currently executes in this order:

```java
MethodHashStore current = MethodHashStore.scan(testSourceRoot);     // line 109: full re-parse
MethodHashStore previous = MethodHashStore.load(methodHashFile);    // line 111: loaded after scan
return current.getChangedMethods(previous);
```

`MethodHashStore.scan()` parses every `.java` file under `testSourceRoot` using the source parser — for 1000 test files this means 1000 file reads + 1000 AST parses, regardless of how many files actually changed.

`MethodHashStore.scanIncremental(testSourceRoot, previous)` was specifically added (F3) to avoid this: it reads each file as raw bytes, computes a `SHA-256` fingerprint, and only re-parses files whose fingerprint differs from the `previous` store. For a suite where 999 of 1000 files haven't changed, `scanIncremental` does 1000 cheap byte-reads and 1 parse; `scan` does 1000 parses.

The fix requires loading `previous` first — it's loaded two lines later at line 111, but by that point `scan` has already done all the work. Swapping the two lines and changing `scan` to `scanIncremental(testSourceRoot, previous)` is the complete fix.

**Impact**: For a 1000-file test suite changing 1 file: `scan` takes O(1000 × parse_time) ≈ 200–500ms. `scanIncremental` takes O(1000 × hash_time + 1 × parse_time) ≈ 10–20ms. The saving scales with test suite size and is nearly total on typical incremental builds where few files change.

**Risk**: Low. `scanIncremental` has explicit fallback logic: when `previous` is null (first run) or has no fingerprints, it falls back to full `scan`. The behavioral difference is only in performance, not in which methods are identified as changed.

**Fix**: Swap lines 109 and 111, change `scan(testSourceRoot)` to `scanIncremental(testSourceRoot, previous)`. Three-line change total.

---

### J2. `TreeSet` allocations in `OrderReportPrinter` for display-only sorting — **✅ DONE**

**Location**: `OrderReportPrinter.java:49, 52, 134, 137`  
**File**: `test-order-core/.../OrderReportPrinter.java`

**Problem**: Four sites in `OrderReportPrinter` create a `TreeSet` solely to produce sorted output for console display:

```java
out.println("Changed classes: " + String.join(", ", new TreeSet<>(changed)));
out.println("Changed test classes: " + String.join(", ", new TreeSet<>(changedTests)));
```

`TreeSet` maintains a sorted red-black tree — every insertion is O(log N) with a `Comparable` or `Comparator` call. For 200 changed classes, constructing `new TreeSet<>(changed)` performs 200 tree insertions. The result is used exactly once: to produce a joined string for `println`. The `TreeSet` is then immediately garbage-collected.

The sorted order has no correctness impact — it only makes the console output easier to read. Removing the sort entirely would produce the same information in a different order.

**Impact**: Four `TreeSet` construction operations per report print, each O(N log N) for N changed classes or tests. For N = 200: ~1600 comparisons total per report. Absolute time is trivial (<1ms), but the pattern repeats on every `show-order` and `select` invocation that produces output.

**Risk**: None to correctness. The only observable change is console output ordering. If users or scripts parse the output for sorted class names, removing the sort would be a breaking change — but console output of this form is generally not machine-parsed. Verify with a search for downstream parsing of this output before changing.

**Fix**: Either drop the sort (pass the set directly to `String.join`) or replace `new TreeSet<>(changed)` with `changed.stream().sorted().collect(Collectors.toList())` which avoids the TreeSet overhead for a one-shot sort. The stream approach is slightly more readable about intent: "sort once, join, discard".

---

### J3. Per-method string concat key in `StructuralDiff.groupMethods()` — **✅ DONE**

**Location**: `StructuralDiff.java:406, 290, 294`  
**File**: `test-order-core/.../changes/StructuralDiff.java`

**Problem**: Three sites in `StructuralDiff` build a `"fqcn#name"` composite key by string concatenation:

```java
// groupMethods (line 406): called for old and new method lists per changed file
map.computeIfAbsent(m.enclosingFqcn() + "#" + m.name(), k -> new ArrayList<>()).add(m);

// diffFields (lines 290, 294): called for all fields in old and new model
oldFields.putIfAbsent(f.enclosingFqcn() + "#" + f.name(), f);
newFields.putIfAbsent(f.enclosingFqcn() + "#" + f.name(), f);
```

Each concatenation allocates a new `String` object. For `groupMethods`, this is called once per `MethodNode` per file diff — for a file with 100 methods in both old and new versions, that's 200 String allocations per file. For `diffFields`, a similar count for field nodes.

The `StructuralDiff` diff runs once per changed file. With 50 changed files per build, this is 50 × 200 = 10K String allocations for methods alone, plus the same for fields.

**Impact**: Minor in absolute terms — 10–20K small String allocations add ~0.5–2ms to the diff phase. The diff is parallelized (`parallelStream` at line 159), so the per-file cost is divided by CPU count. The real concern is that `MethodNode` and `FieldNode` records already carry `enclosingFqcn()` and `name()` — a two-field record key would provide O(1) equality without any string allocation.

**Risk**: None. The key is local to these methods (not exposed in any public API) and only needs to be consistent within a single method call. A two-field record key produces correct `equals`/`hashCode` via record semantics.

**Fix**: Define a local record `record MemberKey(String fqcn, String name) {}` inside `groupMethods` and `diffFields`, replacing the string concat keys. Alternatively, since both `MethodNode` and `FieldNode` are already records, use them directly as map keys — record equality is structural, so two `MethodNode` instances with the same fqcn/name/signature are already equal. Using the node directly as the key eliminates the concat entirely.

---

## Category K: Optimizer / ML Hot Paths (added 2026-05-22)

### K1. `Integer[]` boxing in `APFDCalculator.sortedIndicesByScore` — **✅ DONE** (pre-computed scores array, comparator references array instead of recomputing)

**Location**: `APFDCalculator.java:155–162`  
**File**: `test-order-core/.../APFDCalculator.java`

**Problem**: `sortedIndicesByScore` needs to sort a range of indices by a parallel `double[]` of scores. Java's `Arrays.sort(T[], Comparator)` requires an object array, so the code boxes primitive `int` indices into `Integer[]`:

```java
Integer[] indices = new Integer[n];           // allocate n Integer objects
for (int i = 0; i < n; i++) indices[i] = i;  // box each int
Arrays.sort(indices, (a, b) -> Double.compare(scores[b], scores[a]));
int[] result = new int[n];
for (int i = 0; i < n; i++) result[i] = indices[i];  // unbox each Integer
```

This is called inside `ScoringOptimizer.evaluateWeights` or `evaluateExpandingWindow` — once per individual per generation in the genetic algorithm. With 200 outcomes, 50 population size, 30 steady-fitness generations, and 10 cross-validation folds, `sortedIndicesByScore` is invoked approximately 15,000 times per `optimize()` run, allocating 200 `Integer` objects each time = 3M `Integer` allocations. The `Integer[]` array itself adds another 3M array element references (each Integer is a heap object on the JVM's object heap).

**Why this matters for the genetic optimizer specifically**: The genetic optimizer is the most CPU-intensive path in the entire codebase — it's the one path where shaving 10–20% off a tight inner loop produces a user-visible speedup. GC pressure from 3M boxed integers forces more frequent young-gen collections, which introduces pause jitter in the optimizer's timing.

**Impact**: Eliminating the boxing reduces GC pressure significantly and speeds up the sort itself (primitive `long[]` sorts at near-memory-bandwidth speed; boxed `Integer[]` sorts with pointer-chasing through the heap). Estimated optimizer speedup: 10–25%.

**Risk**: None. The output is identical — the same sorted indices are produced. The only change is representation.

**Fix**: Pack `(score, original_index)` into a `long[]` where the upper 32 bits are the score's bits (via `Double.doubleToRawLongBits`) and the lower 32 bits are the index:
```java
long[] packed = new long[n];
for (int i = 0; i < n; i++)
    packed[i] = (Double.doubleToRawLongBits(scores[i]) & 0xFFFFFFFFL00000000L) | i;
Arrays.sort(packed);  // primitive sort, descending requires reversing or negating score bits
int[] result = new int[n];
for (int i = 0; i < n; i++) result[i] = (int)(packed[n - 1 - i] & 0xFFFFFFFFL);
```
Or use a simpler approach: an `int[]` of indices sorted by a custom merge sort over the scores — or just accept the single-array overhead and use a `double[]` copy sorted in reverse alongside an index-tracking pass.

---

### K2. `extractPackage` called O(tests × stats) times in `MLFeatureExtractor.extract` — **✅ DONE**

**Location**: `MLFeatureExtractor.java:261–281, 349`  
**File**: `test-order-core/.../ml/MLFeatureExtractor.java`

**Problem**: `extract()` is called once per test class per ML evaluation. The most expensive `extractPackage` call is at line 349, inside the stats iteration loop:

```java
// Called once per entry in stats map, per test class
for (var entry : stats.entrySet()) {
    if (!entry.getKey().equals(testClass) && extractPackage(entry.getKey()).equals(testPkg)) {
        // accumulate package failure rate
    }
}
```

`extractPackage` is `fqcn.lastIndexOf('.') + substring` — two operations on the full class name string. At line 349 it's called once per `stats` map entry per test class being extracted. If `stats` has 1000 entries and there are 500 test classes being scored: 500 × 1000 = 500K `extractPackage` calls just for this line.

The `stats` map doesn't change between test classes in a single `extractAll()` invocation. The package for each stats entry can be computed once and cached in a pre-built `Map<String, String>` (className → package) before the per-test loop begins.

Additionally, lines 261–281 call `extractPackage` inside loops over `deps` and `changedClasses` — sets that are also fixed for a given `extract()` call. These can be pre-computed as `Set<String>` of packages before entering the per-test loop in `extractAll()`.

**Impact**: Pre-computing the stats package map reduces line 349 from O(stats × tests) to O(stats + tests). For 500 tests × 1000 stats: 500K → 1500 calls — a 300× reduction for that loop. The `deps` and `changedClasses` package sets (lines 261–281) reduce from O(deps × tests) to O(deps) by moving computation outside the per-test loop.

**Risk**: Low. `extractPackage` is a pure function with no side effects. Caching its results cannot change the output. The only precondition is that the `stats` map, `deps`, and `changedClasses` sets don't change during a single `extractAll()` invocation — which they don't, as these are captured inputs.

**Fix**: In `extractAll()` (or wherever `extract()` is called in a loop), pre-compute:
```java
Map<String, String> statsPkgCache = new HashMap<>(stats.size());
for (String name : stats.keySet()) statsPkgCache.put(name, extractPackage(name));
Set<String> changedPackages = changedClasses.stream().map(MLFeatureExtractor::extractPackage).collect(Collectors.toSet());
```
Then pass these pre-computed maps/sets into `extract()` instead of recomputing them per test.

---

### K3. Per-method `HashSet` allocation in `MethodScorer.computeSetCoverBonuses` — **✅ DONE**

**Location**: `MethodScorer.java:155`  
**File**: `test-order-core/.../MethodScorer.java`

**Problem**: `computeSetCoverBonuses` iterates over methods and, for each method with non-empty `methodDeps`, allocates a `new HashSet<>()` to collect the intersection with `changedClasses`:

```java
Set<String> covered = new HashSet<>();
for (String dep : methodDeps) {
    if (changedClasses.contains(dep))
        covered.add(dep);
}
```

This is called once per method per test class scored. The `covered` set is immediately used to build the `coverage` map entry (`coverage.put(methodKey, covered)`), which is then passed to `SetCoverComputer`. The HashSet is used only for membership testing within the set-cover algorithm.

For 50 tests × 100 methods each = 5000 `new HashSet<>()` allocations per `score()` call. Each HashSet starts with an internal `Object[]` of size 16 (the default initial capacity), even though the `covered` set will typically have 1–5 elements. The backing array is 6–10× larger than needed.

**Why this matters**: `MethodScorer` is called during method-level scoring, which runs during `since-last-run` mode after each test run. It's invoked more frequently than class-level scoring for incremental builds.

**Impact**: 5000 HashSet allocations per `score()` call, each with a 16-element backing array (128 bytes). Total: ~640KB of object allocation per scoring pass — mostly immediately collectible but still creating GC pressure on the young generation.

**Risk**: None, with one important caveat: the `covered` set is placed into the `coverage` map and passed to `SetCoverComputer`. If `SetCoverComputer` mutates it, changing the collection type would break it. Verify that `SetCoverComputer` only reads the sets. If so, `List.copyOf(filteredDeps)` or even `Set.copyOf(filteredDeps)` (immutable) are safe and avoid HashSet overhead.

**Fix (preferred)**: Check non-emptiness first with `methodDeps.stream().anyMatch(changedClasses::contains)` to avoid allocating when `covered` would be empty. For the non-empty case, use `methodDeps.stream().filter(changedClasses::contains).collect(Collectors.toUnmodifiableSet())` — `toUnmodifiableSet()` uses a compact internal implementation. Alternatively, initialize `HashSet` with capacity `Math.min(methodDeps.size(), changedClasses.size())` to avoid the 16-slot default.

---

## Category L: Unnecessary Zero-Entry Caching and Minor Per-Event Waste (added 2026-05-22)

### L1. Zero-overlap cache entries allocate `new HashSet<>()` per non-affected test — **✅ DONE**

**Location**: `TestScorer.java:317–321`  
**File**: `test-order-core/.../TestScorer.java`

**Problem**: After computing overlap for affected tests, `computeSetCoverBonuses` fills in zero-overlap entries for the remaining tests:

```java
// Lines 317-321
for (String test : testClassNames) {
    if (!affectedTests.contains(test)) {
        cachedOverlapCounts.put(test, 0);
        cachedOverlapClasses.put(test, new HashSet<>());  // never read meaningfully
    }
}
```

The two read sites that consume this cache already specify correct default values for absent keys:
- Line 367: `cachedOverlapCounts.getOrDefault(testClassName, 0)` — default 0 is correct
- Line 478: `cachedOverlapClasses.getOrDefault(testClassName, Set.of())` — default empty set is correct

Storing `0` and `new HashSet<>()` for non-affected tests produces map entries that are semantically identical to the absent-key defaults. The only effect is storing ~2K extra map entries and allocating ~2K `HashSet` objects that are immediately functionally equivalent to `Set.of()`.

For a 2000-test suite with 10 affected tests, this loop runs 1990 times — allocating 1990 `HashSet` objects (each with a 16-slot `Object[]` backing array ≈ 128 bytes each = ~250KB of immediate garbage) before the first test is scored.

**Impact**: Up to ~2K HashSet allocations and map entries per scoring cycle. For projects with large test suites and small change sets (which is the normal case for incremental `since-last-run` runs), this zero-caching loop can dominate the `computeSetCoverBonuses` setup cost, since the actual overlap computation for the few affected tests is trivially fast.

**Risk**: None. This is a provably safe deletion: removing lines 317–321 changes nothing observable because the two read sites already handle absent keys correctly. Verify by grepping all usages of `cachedOverlapCounts` and `cachedOverlapClasses` — both only use `getOrDefault` with the same defaults.

**Fix**: Delete lines 317–321 entirely.

---

### L2. `methodKey` string concat duplicated in `TelemetryListener.executionFinished` — **✅ DONE**

**Location**: `TelemetryListener.java:295, 307`  
**File**: `test-order-junit/.../junit/TelemetryListener.java`

**Problem**: Inside the `source instanceof MethodSource methodSource` branch of `executionFinished`, `methodKey` is declared and assigned twice with identical expressions:

```java
// Line 295 — first declaration
String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
if (!testIdentifier.getType().isTest()) {
    containerTrackedMethods.remove(methodKey);
    ...
}
// Line 307 — re-declared with same expression, shadowing the first
String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
Long start = methodStartTimes.remove(testIdentifier.getUniqueId());
```

Both declarations are inside the same `instanceof` block and reference the same `methodSource` object. The second `String methodKey` declaration literally re-computes the same string value: `getClassName()` and `getMethodName()` are stable getters that return the same values for the same `methodSource`. The second declaration shadows the first (it would be a compile error if they were in the same scope level, but they're in different inner blocks).

The result: every method execution event allocates two identical strings for `methodKey` where one is sufficient.

**Impact**: One extra String allocation per method execution event. On a 200-test suite with 10 test methods per class = 2000 `executionFinished` calls, each producing one redundant String: 2000 extra allocations of ~40–80 bytes each = ~100–160KB of redundant allocation per test run. Trivial in absolute terms.

**Risk**: None. The second declaration can be removed — replace the second `String methodKey = ...` with just `methodKey` (reusing the variable from line 295). Verify that the first declaration is visible in scope at line 307 after removing the inner `if` block's scoping. If the inner `if` block introduces a new scope, hoist the declaration before the inner `if`.

**Fix**: Hoist `String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();` before the inner `if (!testIdentifier.getType().isTest())` check at line 296, and remove the duplicate declaration at line 307.

---

### L3. Double string concat for static field member keys in `StructuralChangeAnalyzer.fromDiffs` — **✅ DONE**

**Location**: `StructuralChangeAnalyzer.java:70, 74`  
**File**: `test-order-core/.../changes/StructuralChangeAnalyzer.java`

**Problem**: `fromDiffs` (line 50) iterates over structural diff changes. For a change that is both a member change and a static field change, `fqcn + "#" + memberName` is computed twice:

```java
String memberName = resolveMemberName(change);         // line 68
if (memberName != null) {
    memberKeys.add(change.fqcn() + "#" + memberName);  // line 70 — first concat
    byClass.computeIfAbsent(change.fqcn(), k -> new LinkedHashSet<>()).add(memberName);

    if (change.category() == FIELD && isStaticFieldChange(change)) {
        staticFields.add(change.fqcn() + "#" + memberName);  // line 74 — same concat again
    }
}
```

Both lines 70 and 74 build `change.fqcn() + "#" + memberName`. `fqcn()` and `memberName` are both already computed (line 68 resolves `memberName`; `change.fqcn()` is a direct field accessor). The two strings are identical.

`fromDiffs` is called once per `StructuralDiff.FileDiff` during change detection. For a typical build with 10–50 changed files, each with a mix of method and field changes, this runs dozens of times. Static field changes are a small subset of total changes.

**Impact**: Negligible in absolute terms — one extra String allocation per static field change, typically 1–5 per build. This is the smallest item in the document.

**Risk**: None. Hoisting to a local variable is trivially correct — `fqcn()` and `memberName` don't change between line 70 and line 74.

**Fix**: Add `String memberKey = change.fqcn() + "#" + memberName;` before line 70, then use `memberKey` for both `memberKeys.add(memberKey)` (line 70) and `staticFields.add(memberKey)` (line 74). Three-line change.

---

## Priority Matrix

| Priority | ID | Description | Impact | Risk | Effort |
|----------|----|-------------|--------|------|--------|
| **High** | J1 | Wire `scanIncremental` into `detectChangedMethods` | 100–500ms/invocation | Low | Trivial |
| **High** | A1 | Replace direct merge on flush with append-only/aggregator | 100–500ms/fork | Low | Medium |
| **High** | A2 | Batch `.deps` writes into one file | 50–100ms / 1s on HDD | Low | Medium |
| **High** | D1 | Eliminate inner-loop string concat in member overlap | 100K+ allocs | None | Low |
| **High** | H6 | Use inverted index in `computeSetCoverBonuses` pre-scan | 50–200ms on large sets | None | Low |
| **High** | H1 | Single try-catch for `StructuralDiff` source load | tens of ms | None | Trivial |
| **High** | H12 | Cache loaded `DependencyMap` per (path, mtime) | 50–500ms × N modules | Medium | Medium |
| **Medium** | L1 | Drop zero-overlap cache entries in `TestScorer` | ~2K HashSet allocs/cycle | None | Trivial |
| **Medium** | K2 | Pre-compute package sets in `MLFeatureExtractor.extract` | 500K ops → O(1) | Low | Low |
| **Medium** | K1 | Eliminate `Integer[]` boxing in `APFDCalculator.sortedIndicesByScore` | 6M allocs/optimize | None | Low |
| **Medium** | K3 | Skip per-method `HashSet` alloc in `MethodScorer.computeSetCoverBonuses` | 5K allocs/score | None | Low |
| **Medium** | A5 | Incremental reverse `ClassIdMap` array | Avoids rebuild | Low | Low |
| **Medium** | B2 | Sorted batch insert for member bitmaps in `DEP_GROUPS` | Faster save | None | Low |
| **Medium** | H3 | Skip defensive HashSet copy on load | 10–20MB GC | Low | Low |
| **Medium** | H4 | Replace `readAllLines().stream()` with `Files.lines` | 30–50ms aggregate | None | Trivial |
| **Medium** | H5 | `HashMap` instead of `LinkedHashMap` for runtime maps | ~5% smaller heap | Low | Low |
| **Medium** | H9 | Drop `synchronized` on `mergeFromAgent` | enables JIT | Low | Trivial |
| **Medium** | H11 | Cache `unmodifiableSet(keySet())` in `testClasses()` | 10–20ms GC | Low | Trivial |
| **Low** | L2 | Deduplicate `methodKey` concat in `TelemetryListener` | 2K allocs/run | None | Trivial |
| **Low** | L3 | Hoist static field member key in `StructuralChangeAnalyzer` | Trivial | None | Trivial |
| **Low** | J2 | Drop `TreeSet` in `OrderReportPrinter` display output | Trivial | None | Trivial |
| **Low** | J3 | Two-field key record in `StructuralDiff.groupMethods` | 1–5ms/diff | None | Low |
| **Low** | A3 | Remove `.sorted()` in `.deps` write path | Minor | None | Trivial |
| **Low** | H2 | `containsKey` + `get` → single `get` in state migration | ~5% deserialize | None | Trivial |
| **Low** | H7 | `HashMap` capacity hints for known-large maps | 5–10ms | None | Trivial |
| **Low** | H8 | Replace `String.format` in scoring report | <1ms | None | Trivial |
| **Low** | H10 | Hoist `substring(0, dollar)` in inverted index loop | 1K allocs | None | Trivial |
| **Low** | H14 | `StringBuilder` with capacity in `DashboardGenerator` (audit) | seconds on large | None | Low |
| **Low** | H15 | Lift `Pattern.compile` out of hot paths (audit) | 50ms / 1k classes | None | Trivial |
| **Low** | H16 | Cache `TestOrderState` parse by mtime within JVM (audit) | 100ms × N CLI calls | Low | Low |

---

## Quick Wins (<30 min, zero risk, reach for these first)

1. **J1** — wire `scanIncremental` into `detectChangedMethods` (load previous first, pass to `scanIncremental`)
2. **L1** — remove zero-overlap cache loop in `TestScorer` (lines 317–321)
3. **H1** — single try-catch for `StructuralDiff` source read
4. **L2** — hoist `methodKey` before the inner-`if` in `TelemetryListener.executionFinished`
5. **L3** — hoist static field member key in `StructuralChangeAnalyzer.fromDiffs`
6. **A3** — drop `.sorted()` from `.deps` write path
7. **H4** — `Files.lines()` instead of `readAllLines().stream()` in `.deps` read paths
8. **H9** — drop `synchronized` from `mergeFromAgent` (all three overloads: lines 971, 1035, 1041)
9. **H10** — hoist `substring(0, dollar)` out of `buildInvertedIndex` loop
10. **H11** — cache `testClasses()` unmodifiable view
11. **H7** — capacity hints on known-size HashMaps
12. **H8** — `String.format` → `DecimalFormat` / concat in scoring report
13. **H15** audit — lift `Pattern.compile` out of hot paths

---

## Verification Approach

For any change in this list:
```
bash scripts/bench_learn_modes_multiproject.sh --quick --repeat 3
```
before + after, comparing min-time across modes (avg is dragged up by GC noise on small projects). The `--quick` mode alone (without `--repeat`) has 2× run-to-run variance and is unreliable for verifying single-percent gains.
