# Overnight Bug Audit — Design Spec

**Date:** 2026-07-19  
**Branch:** overnight/bugfixes  
**Context:** Continuing the BUG-168..173 campaign; systematically audit all four core subsystems for correctness bugs.

## Scope

Four subsystems, each assigned to a parallel audit agent:

| Agent | Subsystem | Files | Bug range |
|-------|-----------|-------|-----------|
| A | Scoring & selection | TestScorer, TestSelector, TieredTestSelector, SetCoverComputer, ScoringConstants | BUG-174..183 |
| B | Reactor ordering & show workflow | ReactorOrderOperation, ShowWorkflow, OrderReportPrinter, AffectedWorkflow | BUG-184..193 |
| C | Method-level scoring | MethodScorer, MethodOrderingEngine, SetCoverComputer | BUG-194..203 |
| D | Change detection & dep map | DependencyMap, ChangeDetectionOps, changes/ subpackage | BUG-204..213 |

Third-party campaign runs concurrently as a separate background agent.

## Per-Agent Protocol

1. Read all assigned files end-to-end.
2. Hunt for known-pattern bugs:
   - `score > 0` used as change-affected proxy (BUG-173 pattern)
   - Missing floor/ceiling on sqrt denominators (BUG-163 pattern)
   - `@Nested` class not collapsed to outer class in new paths (BUG-170/171 pattern)
   - Abstract class leaks into runnable selection (BUG-169 pattern)
   - List/set mutations without snapshots (RunHistoryStorage pattern)
   - Surefire exclude matcher not applied in all selection paths (BUG-168/172 pattern)
   - Off-by-one in budget/topN math
   - Inconsistency between show/reactor/selector logic for the same concept
3. For each confirmed bug:
   - Write minimal fix
   - Write JUnit 5 regression test
   - Commit: `fix(subsystem): <description> (BUG-N)`
4. Produce a brief findings summary at the end.

## Output Contract

- Each fix lives in its own commit on `overnight/bugfixes`
- Bug numbers are reserved per-agent range; unused numbers are skipped
- Regression tests go in `test-order-core/src/test/java/me/bechberger/testorder/`
- No refactoring beyond what directly fixes the bug

## Third-Party Campaign

Runs `scripts/third_party_test_plan.sh full` concurrently. Results inform whether recent fixes (BUG-168..173) changed any CAUGHT/MISSED outcomes.
