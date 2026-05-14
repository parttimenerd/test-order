# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Source packages from `src/main/java` are now **always auto-detected** and used as the instrumentation filter — zero configuration needed in most cases
- `includePackages` is now **additive** — user-specified prefixes are merged with auto-detected source packages instead of replacing them
- Redundant prefix minimisation — e.g. `com.example` and `com.example.app` are collapsed to `com.example`

### Changed
- `filterByGroupId` now only acts as a fallback when no source packages are detected (previously it was the primary logic)
- `includePackages` property description clarified as "additional" prefixes

### Deprecated

### Removed

### Fixed
- **Critical: groupId/package mismatch caused empty dependency index** — Source packages are now always auto-detected from `src/main/java`; groupId is only a fallback. This fixes the case where inheriting a parent POM's groupId caused the agent to miss all application classes.
- **warnIfNoDeps missed framework-only deps** — The warning check now recognizes deps containing only `me.bechberger.testorder.*`, `org.junit.*`, and `org.opentest4j.*` as effectively empty.
- Plugin compilation failure — `TestOrderStateAssert` referenced removed `failureWindowDays()` and `failureRecordCount()` methods (stale after exponential decay refactor)
- Failure scores now decay on all-pass runs (previously only decayed when new failures existed)
- Change complexity scoring now respects structural (member-level) exclusion — only deps that match at member level contribute complexity
- Backward-compat failure migration clamps `daysAgo` to ≥ 0 (clock skew could amplify scores)
- ChangeComplexity.serialise() Javadoc corrected (stores normalised values, not raw sizes)
- **Critical: Gradle integration test deadlock** — `configureStateLocking` acquired a file lock in the Gradle daemon's `doFirst`, while `TelemetryListener` tried to acquire the same lock in the worker JVM. Since `doLast` (which releases the lock) can't run until the worker finishes, this caused an infinite deadlock on every Gradle test run. Removed the redundant outer lock.
- **MethodScorer set-cover bonus was a no-op** — `computeSetCoverBonuses()` divided by `SET_COVER_DECLINE` after multiplying, giving all methods the same bonus. Now stores bonus before reducing, matching `TestScorer`.
- **NPE in TestOrderState static init when resource missing** — `loadResourceConfig()` now checks for null InputStream from `getResourceAsStream()` and throws a clear `IllegalStateException` instead of a bare NPE that produces a confusing `ExceptionInInitializerError`.
- **NPE in readConfigInt/readConfigDouble** — Missing TOML config keys now produce a clear error message instead of an NPE during static initialization.
- **Resource leak in FixedOrderClassOrderer** — Stream from `getResourceAsStream()` was not closed on IOException path. Now uses try-with-resources.
- **Silent failures in FixedOrderClassOrderer/FixedOrderMethodOrderer** — IOExceptions when reading order files during OD-detection were silently swallowed, causing tests to run in default order without any warning. Now logs a warning message.
- **TelemetryListener shutdown hook race condition** — `emergencySave()` now snapshots all concurrent collections before iterating them, preventing `ConcurrentModificationException` if the shutdown hook fires while `testPlanExecutionFinished()` is clearing the maps.
- **Invalid historyMaxRuns silently ignored** — `applyHistoryMaxRuns()` now logs a warning when the `testorder.history.maxRuns` system property has an invalid value (non-numeric, zero, or negative) instead of silently swallowing the error.
- **MavenTestRunner used simple class names for `-Dtest=`** — `detect-dependencies` passed only the simple class name (e.g., `FooTest`) to Surefire's `-Dtest=` filter, which caused collisions when different packages contained identically-named test classes. Now uses fully-qualified class names.
- **MavenTestRunner method order file path not quoted** — The `-Dtestorder.fixed.method.order.file=` system property was not quoted, causing method-level OD detection to fail when project paths contain spaces.
- **ConflictGraphBuilder misclassified SCREAMING_SNAKE_CASE constants as mutable** — `isLikelyMutableField()` incorrectly treated ALL_CAPS fields with underscores (e.g., `MAX_SIZE`, `DEFAULT_TIMEOUT`) as mutable due to a `|| field.contains("_")` clause, creating false positive conflict edges between tests sharing only constant fields.
- **PrepareMojo autoAggregate crash on corrupted `.deps` files** — When `.deps` files exist but the index doesn't, auto-aggregation failures now log a warning and fall through to other recovery options (learn mode, etc.) instead of immediately terminating the build.
- **TestSelector.selectTopN() over-selection** — New tests (from change detection) were not counted towards the `topN` budget, so `selectTopN()` could select up to `topN + newTests` classes instead of respecting the limit. Now counts already-selected new tests towards the budget; only `@AlwaysRun` classes are truly additive.
- **Benchmark temp directory leak** — `CoreAlgorithmBenchmark.SerializationState` created a temp directory per trial but never cleaned it up. Added `@TearDown` to delete temp files after each benchmark run.

### Security

## [0.0.1] - 2026-04-13

Initial alpha release.

### Added
- Java agent for recording test class dependencies via bytecode instrumentation (javassist)
- JUnit 5 `ClassOrderer` that prioritizes tests by dependency overlap with changed classes
- `TestExecutionListener` for learn-mode telemetry (sets current test class)
- Dependency map aggregation and I/O (text-based index format)
- File hash store with LZ4 compression for "since-last-run" change detection
- Git-based change detection (since last commit, uncommitted changes)
- Unified `ChangeDetector` with four modes: `SINCE_LAST_RUN`, `SINCE_LAST_COMMIT`, `UNCOMMITTED`, `EXPLICIT`
- CLI tool (`test-order`) with subcommands: `aggregate`, `affected`, `stats`, `hash-snapshot`, `changed`, `run`
- Maven plugin with `prepare`, `aggregate`, and `snapshot` goals
- Auto/learn/order mode support in Maven plugin
- Agent option parsing via [femtocli](https://github.com/parttimenerd/femtocli) agent-args mode
- Example project demonstrating plugin usage
