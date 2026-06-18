# Phase 3 Architecture Review & Fixes

## Summary
Comprehensive architectural review of codebase identified 11 significant design issues. Fixed 5 immediately-actionable issues. 6 larger issues require more extensive refactoring and are documented for future work.

## Fixed Issues (5)

### Issue #37: IndexCollectorServer Registry Cleanup Race
**Problem**: Registry entry removed AFTER merge completes; if merge fails partway, stale state remains in registry.
**Fix**: Move `jvmRegistry().remove()` to execute BEFORE merge starts. Prevents concurrent drain attempts from using stale state.
**Files**: IndexCollectorServer.java:243

### Issue #38: IndexCollectorServer Unbounded Thread Creation
**Problem**: One thread created per client connection with no limit; ThreadLocal READ_BUF leaks for accumulated threads; activeHandlers polling may timeout under load.
**Fix**: 
- Replace unbounded thread creation with fixed ThreadPoolExecutor (50 max threads)
- Add RejectedExecutionException handling to gracefully reject connections when full
- Eliminates resource exhaustion attack surface
**Files**: IndexCollectorServer.java:44-45, 103, 168-169, 443-459, 420

### Issue #39: DependencyMap Cache Coordination
**Problem**: invertedIndex and depFrequencies volatile fields invalidated ad-hoc throughout code; no explicit API; callers must know to re-request after mutations.
**Fix**:
- Extract `invalidateCaches()` as explicit public method
- Call from `put()` and document requirement for `putDirect()` callers
- Add clear documentation of cache invalidation pattern
**Files**: DependencyMap.java:196-223, 254-257

### Issue #41: ClassOrderingEngine Mixed Concerns
**Problem**: Single 89-line `setup()` method mixes file I/O, configuration resolution, error handling with deduplication, structural analysis, and complexity computation. Untestable in isolation.
**Fix**:
- Extract 4 private phase methods: `setupIO()`, `setupState()`, `setupAnalysis()`, `setupComplexity()`
- Main `setup()` method now 24 lines, composes phases
- Enables isolated testing and custom composition
**Files**: ClassOrderingEngine.java:47-181

### Issue #44: Unchecked Cast Warnings
**Problem**: Multiple `@SuppressWarnings("unchecked")` for generic Map casts (TestOrderState, DependencyMap, DashboardGenerator); no type-safe alternatives.
**Fix**:
- Create `TypeSafety` utility class with static methods
- Provides: `asMap(Object, Class<V>)`, `asObjectMap()`, `asDoubleMap()`, `asIntMap()`
- Validates types at runtime, returns empty collections on failure (safe defaults)
**Files**: TypeSafety.java (new)

## Remaining Issues (6) - Require Larger Refactoring

### Issue #35: TestOrderState - Monolithic God Class (1441 lines)
**Problem**: Mixes 7+ distinct concerns (weights, persistence, metrics, run history, static coordination, configuration, optimizer).
**Scope**: 50+ public methods; 20+ inner types
**Recommended Fix**: Split into `ScoringWeightManager`, `TestMetricsTracker`, `RunHistoryStorage`, `TestOrderStatePersistence`; keep TestOrderState as facade
**Estimated Effort**: 4-6 hours
**Impact**: High - core class affecting many subsystems

### Issue #36: TestScorer - Dual Overlap Scoring Paths (783 lines)
**Problem**: Two separate code paths (set-cover pre-computed vs. weighted IDF-based) with different scales and duplicated overlap logic. Makes scoring inconsistent and hard to maintain.
**Recommended Fix**: Unify via single "compute overlap and score" function or make set-cover pure pre-selection (reorder only, no point changes)
**Estimated Effort**: 2-3 hours
**Impact**: Medium - affects test prioritization accuracy

### Issue #40: DashboardGenerator - Giant Method (883 lines)
**Problem**: `buildData()` method manually constructs entire JSON tree inline with deeply nested LinkedHashMap calls. Inefficient prefix matching (nested loops).
**Recommended Fix**: Extract `buildProjectInfo()`, `buildWeights()`, `buildTestEntries()`, `buildRunHistory()`, `buildHealthReport()` methods; use builder pattern
**Estimated Effort**: 3-4 hours
**Impact**: Low - UI generation, no production logic

### Issue #42: SourceFileModel - Complex Regex Patterns (2847 lines)
**Problem**: BOUNDED_GENERICS nested 10 levels; METHOD_ISLAND inlines 7+ components; fragile to changes; no regex caching.
**Recommended Fix**: Extract modifier patterns to constants; use regex factory; cache parse results per source file (mtime-keyed)
**Estimated Effort**: 3-4 hours
**Impact**: Low - parser stability only

### Issue #43: Tool - 13 Nested Command Classes (724 lines)
**Problem**: All command implementations (Aggregate, Affected, Stats, etc.) as nested static inner classes in single massive file. Coupling to parent via static helpers.
**Recommended Fix**: Move to `commands/` package; extract `CommandUtils` utility class
**Estimated Effort**: 2-3 hours
**Impact**: Very Low - CLI organization only

### Issue #41: Distributed Concurrency Without Coordination (89+ uses)
**Problem**: Each class implements different thread-safety strategy (ConcurrentHashMap, synchronized, volatile, atomics). No coherent memory visibility model. No thread-safety contract documentation.
**Recommended Fix**: Standardize on one threading model; add @ThreadSafe/@NotThreadSafe/@GuardedBy annotations; document per-class thread-safety contract
**Estimated Effort**: 8-12 hours (audit + refactoring)
**Impact**: High - affects all concurrent code paths

## Test Results
All fixes compile successfully. No breaking changes to public APIs (only additions: TypeSafety.java, ClassOrderingEngine phase methods, DependencyMap.invalidateCaches()).

## Future Work Priority
1. **Issue #35** (TestOrderState) - Critical for maintainability as codebase grows
2. **Issue #41** (Concurrency) - Important for correctness and reasoning about thread-safety
3. **Issue #36** (TestScorer) - Affects prioritization accuracy
4. Remaining issues (lower priority, mostly organizational/maintenance)
