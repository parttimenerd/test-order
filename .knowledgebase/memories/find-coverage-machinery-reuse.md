---
id: find-coverage-machinery-reuse
title: ~80% of a coverage-closure verifier already exists as reusable pieces
kind: finding
tags: [coverage, set-cover, reuse]
applies_to: [test-order-core/src/main/java/me/bechberger/testorder/ops/coverage/, test-order-core/src/main/java/me/bechberger/testorder/SetCoverComputer.java]
source: proper-test-selection/find-coverage-machinery-reuse
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# ~80% of a coverage-closure verifier already exists as reusable pieces

- Universe: `ChangedMembers` (classes, `fqcn#member` keys, static-field keys, change kinds) built by the FULL analysis pipeline (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java structural + bytecode merge) — but NOT in the select path (see find-select-path-reduced-analysis).
- Per-test coverage sets: `StructuralChangeAnalyzer.computeOverlapClasses` (the exact join test↔changed elements), already called per-test by `TestScorer.computeSetCoverBonuses` (test-order-core/src/main/java/me/bechberger/testorder/TestScorer.java:348-371) — but only as a soft score bonus, never a hard constraint.
- Solver: `SetCoverComputer` (generic greedy min-cover with lazy recheck) — proven at class level (TestScorer) and per-class method level (test-order-core/src/main/java/me/bechberger/testorder/MethodScorer.java:146-186, universe = changed classes, per-test-method items).
- Inversion oracle: `CoverageOperation.analyze` builds prodClass→tests and prodClass→member→tests maps — but is a after-the-fact report, never change-aware, and passes `Set.of()` for allMembers so uncovered-member reporting is dead code (test-order-core/src/main/java/me/bechberger/testorder/ops/coverage/CoverageOperation.java:91-96, test-order-core/src/main/java/me/bechberger/testorder/ops/coverage/ClassCoverage.java:78-93).
- Staleness patch: `StaticCallGraphAnalyzer` reverse call graph can grow the universe to call-reachable members; full closure is cheap (graph materialized; expand() just stops at depth 4); `degraded` flag is a ready select-all trigger.
- Member universe completeness: SourceFileModel.methodHashes() or ScanResult declared methods can supply allMembers, fixing the CoverageOperation gap.

Missing pieces list is historical — all were implemented on 2026-11-17 (see coverage-closure selection); kept pieces map is current.
