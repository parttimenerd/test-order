---
id: find-design-decision
title: Coverage-closure selection — shipped semantics (universe, cover core, policies)
kind: finding
tags: [design, decision, coverage-closure]
applies_to: []
source: proper-test-selection/find-design-decision
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Coverage-closure selection — shipped semantics

Target: a tool feature of test-order so ITS USERS get coverage-complete selection. Criterion: every changed class/method exercised by >=1 selected test; over-selection acceptable. Scope: core engine + maven affected/tiered + CI/auto + gradle.

Design:
1. Widen the select-path change analysis beyond Options.FOR_SELECTION (keep readOnly=true): compute ChangedMembers + bytecode method keys + StaticCallGraphAnalyzer expansion during selection.
2. Universe = changed classes ∪ changed member keys (member-level granularity — user decision; line-level explicitly out of scope, would need JaCoCo-class per-probe coverage).
3. Per-test coverage sets via StructuralChangeAnalyzer.computeOverlapClasses; SetCoverComputer picks a minimal-ish cover core; the cover core is force-included in the selection BEFORE topN capping and cache-skip (sole-coverer exemption from cache-skip); topN/cache apply only to the remainder.
4. Uncovered changed elements (no exercising test in the index): WARN in log + CI summary by default; -Dtestorder.coverage.enforce=true fails the build; when static analysis is degraded (expansion blowup), fall back to select-all (user decision).
5. Non-source changes (src/main/resources, pom.xml/build.gradle, configs): module becomes unselectable — run ALL tests of that module (user decision).
6. Wiring: apply the closure in AffectedWorkflow/TestSelector (maven affected, gradle testOrderAffected), tiered tier-1 assertion, auto (runRemaining=false path), CiSummaryWriter gains "changed elements covered X/Y" (also fixes the Gradle affected missing-CI-summary parity gap).
