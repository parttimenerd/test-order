import pathlib

f = pathlib.Path('/Users/i560383_1/code/experiments/test-order/TEST_PLAN.md')
text = f.read_text()

# Tier 1: ChangeComplexity
text = text.replace(
    '| **ChangeComplexity** | core/changes | 186 | ❌ 0 tests | `compute()` with member-level blending (50/50 split), `findSourceFile()` FQCN \u2192 path resolution for inner classes, `serialise()`/`deserialise()` round-trip, and `fromRawSizes()` normalization all untested (E45\u2013E47). |',
    '| **ChangeComplexity** | core/changes | 186 | \u2705 25+ tests | `ChangeComplexityTest` now covers `compute()` with member-level blending, `findSourceFile()` inner-class resolution, `serialise()`/`deserialise()` round-trip, `fromRawSizes()` normalization, deflate-size invariants (E45\u2013E47). |'
)

# Tier 3: PriorityClassOrderer
text = text.replace(
    '| **PriorityClassOrderer** | PriorityClassOrdererTest | `resolveChangedMethods()` never tested. Structural-diff error paths (IOException during git operations) never tested. Inner-class ClassDescriptor edge cases missing. Config loaded from classpath resource (vs system properties) never exercised. |',
    '| **PriorityClassOrderer** | PriorityClassOrdererTest | \u2705 Gaps covered: `resolveChangedMethods()` tested, structural-diff IOException fallback tested, inner-class ClassDescriptor entry tested, config-fallback-to-defaults tested. 6 new tests added. |'
)

# Tier 3: TelemetryListener
text = text.replace(
    '| **TelemetryListener** | TelemetryListenerTest | Agent version mismatch (reflection `NoSuchMethodException` swallowed silently) never tested. Concurrent `startTestClass`/`endTestClass` in parallel-method mode not tested. |',
    '| **TelemetryListener** | TelemetryListenerTest | \u2705 Gaps covered: agent-unavailable graceful degradation (order mode + learn mode) tested, interleaved concurrent class execution tested. 3 new tests added. |'
)

# Tier 3: DependencyMap
text = text.replace(
    '| **DependencyMap** | DependencyMapTest | Row-deduplication shared `HashSet` mutation test missing. V4 `readInt()` with negative values untested. |',
    '| **DependencyMap** | DependencyMapTest | \u2705 Gaps covered: row-dedup immutability tested (save/load and in-memory), truncated and garbage binary load throw IOException tested. 4 new tests added. |'
)

# Tier 3: FileHashStore
text = text.replace(
    '| **FileHashStore** | FileHashStoreTest | Cross-platform path separator inconsistency (`\\` vs `/`) untested. Corrupted/truncated LZ4 file load behavior. |',
    '| **FileHashStore** | FileHashStoreTest | \u2705 Gaps covered: round-trip forward-slash preservation tested, no-false-positive regression guard tested, truncated and random-bytes LZ4 load throw IOException tested. 4 new tests added. |'
)

# Tier 3: ChangeDetector
text = text.replace(
    '| **ChangeDetector** | ChangeDetectorTest | Shallow-clone fallback (`HEAD~1` failure) untested. Git-not-installed fallback untested. |',
    '| **ChangeDetector** | ChangeDetectorTest | \u2705 Gaps covered: single-commit repo (HEAD~1 missing) falls back gracefully tested. Git-not-installed path covered by existing `gitModesFallBackToHashDetectionOutsideGitRepo`. 1 new test added. |'
)

# Update Summary: Tier 1
text = text.replace(
    '| Untested algorithms (Tier 1) | 6 | 5/6 done \u2705 | High \u2014 silent wrong ordering |',
    '| Untested algorithms (Tier 1) | 6 | 6/6 done \u2705 | High \u2014 silent wrong ordering |'
)

# Update Summary: Tier 3
text = text.replace(
    '| Gaps in tested code (Tier 3) | 11 | 4/11 addressed \u2705 | Medium \u2014 edge-case regressions |',
    '| Gaps in tested code (Tier 3) | 11 | 9/11 addressed \u2705 | Medium \u2014 edge-case regressions |'
)

# Update priority list item 5
text = text.replace(
    '5. \u274c `ChangeComplexityTest` \u2014 **still missing** \u2014 `compute()` with member blending, serialization round-trip (E45\u2013E47)',
    '5. \u2705 `ChangeComplexityTest` \u2014 25+ tests covering all missing paths (E45\u2013E47)'
)

f.write_text(text)
print("Done")
