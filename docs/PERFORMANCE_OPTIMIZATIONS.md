# Performance Optimization Opportunities

**Status**: Agent instrumentation is at ~0% overhead (down from 40%).  
This document tracks optimization work — completed items are marked **DONE**.

**Last audited:** 2026-06-17

---

## Category A: Agent Shutdown / Flush Path

### A1. `mergeFromAgent` full index round-trip on shutdown — **DONE**

**Location**: `UsageStore.flush()` → `DependencyMap.mergeFromAgent()`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`, `test-order-core/.../DependencyMap.java`

**Problem**: When the Surefire JVM shuts down, `mergeFromAgent` does:
1. Load the full `.test-order/test-dependencies.lz4` index from disk
2. Merge new dependency sets in memory
3. Re-save the entire index

**Fix**: `IndexCollectorServer` — the Maven/Gradle plugin JVM now starts a socket server before tests run. Each forked Surefire JVM sends its dep data over the socket at shutdown (`flush()` in `UsageStore`). The server merges all data once when the test task completes and writes the index a single time. The per-fork full-reload/save is now a fallback used only when no collector port is configured (standalone agent mode).

---

### A2. 200 separate `.deps` file writes — **DONE**

**Location**: `UsageStore.flush()` → `writeDepsFile()` / `writeMethodDepsFiles()` / `writeMemberDepsFiles()`  
**Files**: `test-order-agent/.../runtime/UsageStore.java`

**Problem**: The flush path wrote one file per test class via separate `Files.write()` calls — up to 800 `open→write→close` cycles in `FULL_MEMBER` mode.

**Fix**: Same as A1 — when `IndexCollectorServer` is running, all dep data is sent over the socket in a single binary batch at flush time (`sendBinaryDepsToServer()`). The per-file writes (`writeDepsFile` etc.) are only used as a fallback when no collector port is available.

---

## Category H: Misc Hot-Path Allocations and Lookups

### H12. Index reload on every `select` invocation — **DONE**

**Location**: `Tool.java` / `SelectMojo` — wherever `DependencyMap.load(indexPath)` is called in select mode  
**Files**: `test-order-core/.../Tool.java`, `test-order-maven-plugin/.../maven/SelectMojo.java`

**Problem**: In a Maven multi-module reactor, `mvn test-order:affected test` is executed once per module. Each execution starts a fresh JVM (via the Maven plugin classloader) and calls `DependencyMap.load(indexPath)`. If all modules share the same `.test-order/test-dependencies.lz4` index — the common case — the same file is decompressed and deserialized once per module.

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
| **Done** | A1 | Replace direct merge on flush with IndexCollectorServer | 100–500ms/fork | — | — | **DONE** |
| **Done** | A2 | Batch `.deps` writes via socket protocol | 50–100ms / 1s on HDD | — | — | **DONE** |
| **Done** | H12 | Cache loaded `DependencyMap` per (path, mtime) | 50–500ms × N modules | — | — | **DONE** |
| **Done** | H16 | Cache `TestOrderState` parse by mtime within JVM | 100ms × N CLI calls | — | — | **DONE** |

---

## Quick Wins

No open TODO items remain.

---

## Verification Approach

For any change in this list:
```bash
bash scripts/bench_learn_modes_multiproject.sh --quick --repeat 3
```
before + after, comparing min-time across modes (avg is dragged up by GC noise on small projects). The `--quick` mode alone (without `--repeat`) has 2× run-to-run variance and is unreliable for verifying single-percent gains.
