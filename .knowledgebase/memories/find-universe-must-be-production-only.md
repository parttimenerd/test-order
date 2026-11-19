---
id: find-universe-must-be-production-only
title: Two pitfalls when building a coverage-closure universe (both hit and fixed here)
kind: finding
tags: [coverage-closure, pitfall, test-classes]
applies_to: [test-order-core/src/main/java/me/bechberger/testorder/**]
source: proper-test-selection/find-universe-must-be-production-only
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Two pitfalls when building a coverage-closure universe (both hit and fixed here)

1. TEST classes must never enter the universe: once the select-path analysis includes test-source structural analysis, changed test classes land in ChangedMembers; each test's coverage set then covers itself, so the greedy cover core force-includes everything and defeats topN capping and the remaining-file contract. Filter changedClasses and changedMembers.changedClasses() through test-class classification (nested Outer$Inner included) before building the universe; a changed test class is selected by the changedTestClasses path, never by cover-core.
2. Fresh-learn (no bytecode-hashes.lz4 baseline): ChangeAnalysis's first-run branch marks every production method changed → member universe floods → cover core = all tests. When the user supplied an explicit authoritative changed-class list (BUG-165 contract), the first-run member flood must not override it.

Both pinned by tests: CoverageClosureSelectionTest (+2), SelectionPathAnalysisOptionsTest (+1). Verified: fresh-learn + explicit change + topN=1 → select 1, defer 1, remaining.txt written, universe sane.
