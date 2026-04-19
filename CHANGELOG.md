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

### Security

## [0.1.0] - 2026-04-13

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
