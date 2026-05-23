# Performance Optimization Opportunities

**Status**: Agent instrumentation is at ~0% overhead (down from 40%).  
This document now tracks only remaining optimization opportunities.

Only items marked **TODO** are included.

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


Hey: Shouldn't this be improved with the server component?

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

introduce a better format

---

## Category H: Misc Hot-Path Allocations and Lookups

### H12. Index reload on every `select` invocation — **DONE**

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

**Fix**: Added a static `ConcurrentHashMap<CacheKey, DependencyMap>` cache in `DependencyMap`, keyed by `(absolutePath, lastModifiedTime, fileSize)`. `load()` checks the cache first (one `Files.getLastModifiedTime()` call ≈ 0.1ms vs. 50–150ms for full deserialization). `save()` evicts the entry for the written path so the next `load()` re-reads updated data.

---

### H16. JSON parsing for state/config on every CLI invocation — **DONE** (audit)

**Location**: `TestOrderState.load()` / config loading paths  
**Files**: `test-order-core/.../TestOrderState.java`, `test-order-core/.../TestOrderConfig.java`

**Problem**: Every `tool` sub-command (`tool show`, `tool stats`, `tool ml-stats`, `tool reset`, etc.) is a fresh JVM invocation that parses the `.test-order/state.json` file from scratch. The JSON file contains test run history, durations, failure rates, method weights, and ML state — it grows as more test runs are recorded and can reach hundreds of KB for mature projects.

For a CI pipeline that calls multiple `tool` sub-commands in sequence (e.g., a post-test script that calls `tool stats`, `tool show`, and `tool ml-stats`), the state file is parsed N times unnecessarily.

**Impact**: State load takes 20–80ms per invocation depending on file size. For a pipeline calling 5 `tool` commands: 100–400ms of redundant parsing per CI run. Multiplied across every CI run for the project lifetime, this compounds into meaningful waste.

**Risk**: Low for in-JVM caching — if the state is only loaded once per JVM execution and the JVM terminates after the command, there's no invalidation concern. Medium risk for a persisted cache (daemon mode), where the state file can be modified by a concurrent test run or `aggregate` goal. A mtime/size-based cache key mitigates this.

**Important scoping note**: This is a different problem from H12 (index reload in Maven reactor). H12 is about within-build overhead; H16 is about script-level pipeline overhead. The solution for H16 within a single JVM is simply to not load the state file more than once — no caching infrastructure needed. For multi-JVM script scenarios, a `tool batch` or `tool repl` mode that reads stdin commands and shares in-memory state across multiple operations would be the right long-term fix.

**Fix**: Added a `ConcurrentHashMap<StateCacheKey, TestOrderState>` cache in `TestOrderState`, keyed by `(absolutePath, mtime, size)`. `load()` checks the cache after an existence check (non-existent files return a fresh default without caching). `save()` evicts the entry for the written path.

---

## Priority Matrix

| Priority | ID | Description | Impact | Risk | Effort |
|----------|----|-------------|--------|------|--------|
| **High** | A1 | Replace direct merge on flush with append-only/aggregator | 100–500ms/fork | Low | Medium |
| **High** | A2 | Batch `.deps` writes into one file | 50–100ms / 1s on HDD | Low | Medium |
| **High** | H12 | Cache loaded `DependencyMap` per (path, mtime) | 50–500ms × N modules | Medium | Medium | **DONE** |
| **Low** | H16 | Cache `TestOrderState` parse by mtime within JVM (audit) | 100ms × N CLI calls | Low | Low | **DONE** |

---

## Quick Wins

No <30 minute, zero-risk TODO items are currently listed.

---

## Verification Approach

For any change in this list:
```bash
bash scripts/bench_learn_modes_multiproject.sh --quick --repeat 3
```
before + after, comparing min-time across modes (avg is dragged up by GC noise on small projects). The `--quick` mode alone (without `--repeat`) has 2× run-to-run variance and is unreliable for verifying single-percent gains.
