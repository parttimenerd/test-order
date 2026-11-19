---
id: find-selection-phases
title: Selection = alwaysRun + new + capped top-N affected + randomM diverse + optional cache-skip
kind: finding
tags: [selection, test-selector, contract]
applies_to: [test-order-core/src/main/java/me/bechberger/testorder/TestSelector.java]
source: proper-test-selection/find-selection-phases
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Selection = alwaysRun + new + capped top-N affected + randomM diverse + optional cache-skip

`TestSelector.select()` (test-order-core/src/main/java/me/bechberger/testorder/TestSelector.java, `select()` ~line 118) runs four phases plus an optional cache phase, in order:

1. Phase 0: all `@AlwaysRun` classes (additive, never cap-counted).
2. Phase 1: all NEW tests (absent from the dependency index).
3. Phase 1.5 (since the coverage-closure feature): force-include the SetCoverComputer cover core over the production changed-element universe — BEFORE top-N cap counting, and exempt from cache-skip; only ADDS tests.
4. Phase 2: change-affected tests — affected = `depMap.getAffectedTests(changedClasses) ∪ changedTestClasses` — capped at `topN` when `topN >= 0` (`topN = -1` = no cap); when NO change signal exists, `topN=-1` selects everything and `topN>=0` selects top N by score.
5. Phase 3: `randomM` fast tests picked greedily by Jaccard-distance dependency diversity (random, seedable).
6. Phase 4 (optional `CacheConfig.enabled`): skip-if-unchanged cache — removes non-affected, non-new, non-quarantined tests with pass-streak ≥ minPassStreak from BOTH selected and remaining, capped at `maxSkipFraction` of the suite.

Consequence for a coverage-complete selection contract: by construction, phase 3 can ADD tests unrelated to the change, and phases 2/4 can DROP tests that exercise changed code only when `topN>=0` caps phase 2 or cache-skip is enabled — with `topN=-1` and cache disabled the selected set equals the full class-level affected set plus new plus always-run plus randomM extras. The under/over-selection levers are: `topN`, `randomM`, `CacheConfig`, and the fidelity of `changedClasses` and the dependency index.
