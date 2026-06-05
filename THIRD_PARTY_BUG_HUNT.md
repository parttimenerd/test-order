# Third-Party & Extended QA Bug Hunt — 2026-06-05

This session widened the QA hunt from static code review into runtime testing
across third-party projects, integration tests, docs, error recovery, and the
dashboard UI.  Numbering continues from BUGS.md (last fixed = BUG-22).

---

## Findings Summary

| # | Category | Severity | Status |
|---|----------|----------|--------|
| BUG-23 | Dashboard UI — `historySpan` negative value | Medium | **Fixed** |
| BUG-24 | Dashboard UI — "Fastest/Slowest" KPI blank with no duration data | Low | **Fixed** |
| BUG-25 | Dashboard UI — "Avg 1st Fail Pos" displayed as 0-based instead of 1-based | Low | **Fixed** |
| BUG-26 | Dashboard UI — History Span tooltip showed inverted time range (newest→oldest) | Low | **Fixed** |
| BUG-27 | Docs — `test-order:show-method-order` goal missing from MAVEN_PLUGIN.md | Low | **Fixed** |
| BUG-28 | Docs — `testorder.reactorReorder` / `reactorTopN` / `reactorReorder.dryRun` undocumented | Medium | **Fixed** |
| BUG-29 | Docs — `CLI_REFERENCE.md` missing several config properties | Low | **Fixed** |
| BUG-30 | Dashboard UI — sidebar method name font too small (`.62rem`) | Low | **Fixed** |
| BUG-31 | Script — `third_party_test_plan.sh` used old goal name `select` (renamed to `affected`) | High | **Fixed** |
| BUG-32 | Agent — `recordUsageIdFast` / `recordMemberUsageIdFast` NPE when `s.target` is null | High | **Fixed** |
| BUG-33 | Dashboard UI — FFP chart and run-detail panel showed 0-based position (missed in BUG-25 fix) | Low | **Fixed** |
| BUG-34 | Dashboard UI — Rank heatmap "click to inspect run" selected the wrong run (index inversion) | Medium | **Fixed** |
| BUG-35 | Dashboard UI — "Last 5/Last 10" time filter showed oldest runs instead of newest | Medium | **Fixed** |
| BUG-36 | Dashboard UI — Time budget optimizer used oldest failing run for APFD estimate instead of latest | Low | **Fixed** |
| BUG-37 | Dashboard UI — Flakiness trend "rising/falling" was inverted (newest-first ordering) | Medium | **Fixed** |
| BUG-38 | Dashboard UI — Rising-risk test detection iterated runs newest→oldest, inverting regression slope | Medium | **Fixed** |
| BUG-39 | Dashboard UI — APFD trend insight compared oldest 3 runs (not newest) due to wrong slice direction | Medium | **Fixed** |
| BUG-40 | Dashboard UI — Health arc narrative showed oldest APFD as "latest", inverted clean-streak count | High | **Fixed** |
| BUG-41 | Dashboard UI — `latestRun` returned oldest run (`runs[0]`) instead of newest | High | **Fixed** |
| BUG-42 | Dashboard UI — Run diff compared 2 oldest runs instead of 2 newest runs | High | **Fixed** |
| BUG-43 | Dashboard UI — Simulated APFD used oldest run outcomes instead of newest | Medium | **Fixed** |
| BUG-44 | Dashboard UI — Per-test last-8-run history showed 8 oldest runs, not 8 newest | Medium | **Fixed** |
| BUG-45 | Dashboard UI — Rank trend was inverted (recent=oldest half) in `TestsTab.vue` | Medium | **Fixed** |
| BUG-46 | Dashboard UI — Score sparkline showed oldest 8 runs; score-history tooltip reversed ordering | Medium | **Fixed** |
| BUG-47 | Dashboard UI — `firstSeenMap` iterated newest→oldest so first-seen was always newest run | Medium | **Fixed** |
| BUG-48 | Dashboard UI — `lastFailureInfo` in KpiRow found oldest failure instead of most recent | High | **Fixed** |
| BUG-49 | Dashboard UI — Run detail `prevRunScoreMap` / `runDiff` used `idx+1` (newer) not `idx-1` (older) | High | **Fixed** |
| BUG-50 | Dashboard UI — Run detail nav buttons "‹ older" / "› newer" had their `selectRun` calls swapped | High | **Fixed** |
| BUG-51 | Dashboard UI — Sidebar run-dot tooltip computed wrong run number (ascending formula) | Low | **Fixed** |
| BUG-52 | Dashboard UI — TestsTab history dot tooltip used same wrong ascending run number formula | Low | **Fixed** |
| BUG-53 | Dashboard UI — `selectedFirstSeen.runNum` used `idx+1` instead of `totalRuns-idx` | Low | **Fixed** |
| BUG-54 | Dashboard UI — "Last failed" timestamp showed oldest failure, not most recent | Medium | **Fixed** |
| BUG-55 | Dashboard UI — Test detail charts (pass/fail + score trend) displayed newest→oldest | Medium | **Fixed** |
| BUG-56 | Dashboard UI — `KpiRow` APFD trend delta compared oldest 2 runs instead of newest 2 | Medium | **Fixed** |
| BUG-57 | Dashboard UI — `KpiRow` history browser default showed oldest run info, not latest | High | **Fixed** |
| BUG-58 | Dashboard UI — `KpiRow` current streak computed from oldest run instead of newest | Medium | **Fixed** |
| BUG-59 | Dashboard UI — `KpiRow` "«" button went to newest run (bar.i=0) instead of oldest | Medium | **Fixed** |
| BUG-60 | Dashboard UI — Analytics health timeline bar rendered newest-left despite "oldest-left" comment | Medium | **Fixed** |
| BUG-61 | Dashboard UI — First-failure heatmap showed 12 oldest failing runs instead of 12 newest | Medium | **Fixed** |
| BUG-62 | Dashboard UI — `speedRatioHistoryMap` iterated runs newest-first, inverting speed-trend sparklines | Medium | **Fixed** |
| BUG-63 | Dashboard UI — "Most Unreliable Tests" history dot tooltip used wrong (ascending) run number formula | Low | **Fixed** |
| BUG-64 | Dashboard UI — Run diff title "Changes vs Run #X (previous)" showed wrong run number (off by 2) | Low | **Fixed** |
| BUG-65 | Dashboard UI — `testHistoryMap` O(tests × runs × outcomes) `.find()` causing quadratic slowdown | Medium | **Fixed** |
| BUG-66 | Dashboard UI — `rankHeatmap` re-sorted outcomes per test per run (O(tests×runs×outcomes×log)) | Medium | **Fixed** |
| BUG-67 | Dashboard UI — `flakinessTimeline` O(tests × runs × outcomes) `.find()` causing quadratic slowdown | Medium | **Fixed** |
| BUG-68 | Dashboard UI — operator precedence bug `?? 0 > 0` instead of `(?? 0) > 0` in retirement filter | Low | **Fixed** |
| BUG-69 | Dashboard UI — `flakyTests`, `firstFailHeatmap` O(tests×runs×outcomes) `.find()` patterns | Medium | **Fixed** |
| BUG-70 | Core — `DependencyMap.putDirect()` failed to invalidate `depFrequencies` lazy cache | Low | **Fixed** |
| BUG-71 | Core — `aggregateFromDepsDirectory` bypassed cache invalidation via direct `dependencies.put()` | Low | **Fixed** |
| BUG-72 | Dashboard UI — `speedTrendSvg` color was inverted: green shown for slowing trend, orange for speeding up | Medium | **Fixed** |
| BUG-73 | Dashboard UI — `testsByName` missing from `DashboardState` interface; TypeScript type safety gap | Low | **Fixed** |
| BUG-74 | Dashboard UI — Run history dots tooltip run-number convention (investigated, not a bug — Run #1=newest is consistent across all components) | N/A | **Not a bug** |
| BUG-75 | Core — `MutationAnalysisOperation` converted inner-class `$` to `.` before looking up in dep-map (which uses `$`) | Medium | **Fixed** |
| BUG-76 | Core — `ClassOrderingEngine` cached null from `depMap.get()` in diversity sort; `jaccardDistance(null, ...)` could NPE | Medium | **Fixed** |
| BUG-77 | Dashboard UI — Δ Score column condition inverted: hidden for newest run (most useful), shown for oldest (empty) | Low | **Fixed** |
| BUG-78 | Core — `DashboardGenerator` serialized null SA file path as the string `"null"` instead of null | Low | **Fixed** |
| BUG-79 | Core — `TestOrderState` wrote empty `methodDurations`/`methodDurationVariances` maps to state file when all classes pruned | Low | **Fixed** |
| BUG-80 | Dashboard UI + Core — Mutation score card showed tautological "100%" (sum of kill-share fractions); real score (`totalKilled/totalMutants`) never persisted or displayed | Medium | **Fixed** |
| BUG-81 | Maven plugin — `CollectorLifecycleParticipant` session-drain path not normalized, causing collector lookup miss and data loss | High | **Fixed** |
| BUG-82 | Maven plugin — `tryReorderReactor` called `getTopLevelProject()` without null check (inconsistent with sibling method) | Low | **Fixed** |
| BUG-83 | Maven plugin — `buildId.substring(0, 8)` without length guard could throw `StringIndexOutOfBoundsException` | Low | **Fixed** |
| BUG-84 | Core — `PersistenceSupport` lost `OverlappingFileLockException` cause after 50 retries, producing unhelpful "after 50 attempts" error | Low | **Fixed** |
| BUG-85 | Core — `CiSummaryWriter` `GITHUB_REF.split("/")[2]` without bounds check; throws `ArrayIndexOutOfBoundsException` for malformed PR refs | Low | **Fixed** |
| BUG-86 | Core — `StructuralDiff` initializer diff used `contains()` for classifying ADDED/REMOVED/MODIFIED; misclassified duplicate-hash additions as REMOVED | Medium | **Fixed** |

---

## Bug Details

### BUG-23: `historySpan` computed property showed negative duration

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** "HISTORY SPAN -15m" on the Analytics tab — a negative time span.  
**Root cause:** `d.runs` is sorted ascending (`a.timestamp - b.timestamp`) by
`useDashboard.ts` line 237, so `d.runs[0]` is the *oldest* run and
`d.runs[last]` is the *newest*. The old subtraction was `runs[0] - runs[last]`
(oldest minus newest = negative).  
**Fix:** Reversed to `runs[last].timestamp - runs[0].timestamp`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-24: "Fastest / Slowest" KPI was blank when no duration data available

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** The "Fastest / Slowest" KPI card showed an empty box if neither
`d.fastestTest.value` nor `d.slowestTest.value` had data (e.g. fresh state
with no runs).  
**Root cause:** `v-if` directives on the speed items had no `v-else` fallback.  
**Fix:** Wrapped in `<template v-if>` with a `<span v-else class="kpi-row__no-data">run tests to populate</span>` fallback.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-25: "Avg 1st Fail Pos" showed 0-based position (confusing as "no data")

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** When CartTest was rank 1 and it failed, "Avg 1st Fail Pos" showed
`0` — which looks like "no data" or "undefined" to a user expecting 1-based rank.  
**Root cause:** `firstFailurePosition` in the state is 0-based (`0` = first
test failed), but the dashboard rendered it raw. `Tool.java` already adds `+1`
for its human-readable output.  
**Fix:** Display `runStats.avgFirstFailPos + 1` in both the value and the
color-threshold comparison.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-26: History Span tooltip showed inverted time range (newest → oldest)

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Tooltip read "Time span from first to last recorded run:
05/06/2026 03:02 → 05/06/2026 02:47" — oldest was shown last, newest first.  
**Root cause:** `:title` used `d.runs[d.runs.length-1]` (newest) for the left
side and `d.runs[0]` (oldest) for the right, opposite of the label text.  
**Fix:** Swapped to `d.runs[0]` (oldest) → `d.runs[d.runs.length-1]` (newest).  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-27: `test-order:show-method-order` goal missing from MAVEN_PLUGIN.md

**Source:** `docs/MAVEN_PLUGIN.md` goals table  
**Symptom:** `@Mojo(name = "show-method-order")` exists in
`ShowMethodOrderMojo.java` but was absent from the documented goals table.  
**Fix:** Added row:
> `test-order:show-method-order` — Show method-level priority order (legacy;
> prefer `test-order:show -Dtestorder.show.methods=true`)  
**File:** `docs/MAVEN_PLUGIN.md`

---

### BUG-28: Reactor reordering properties completely undocumented

**Source:** `CollectorLifecycleParticipant.java` — three user-facing system
properties were present in source but absent from all docs:
- `testorder.reactorReorder`
- `testorder.reactorTopN`
- `testorder.reactorReorder.dryRun`

**Fix:** Added "Automatic Reactor Reordering" sections to both
`docs/MULTI_MODULE_SETUP.md` and `docs/CLI_REFERENCE.md`.  
**Files:** `docs/MULTI_MODULE_SETUP.md`, `docs/CLI_REFERENCE.md`

---

### BUG-29: Several config properties missing from CLI_REFERENCE.md

**Source:** Cross-check of `MavenPluginConfigKeys.java` against
`docs/CLI_REFERENCE.md`  
**Missing properties found:**
- `testorder.showMethodOrder.explain` (Show section)
- `testorder.showStaticAnalysis.verbose` (Show Static Analysis)
- `testorder.download.fallbackToLearn` (Download)
- `testorder.metrics.output` (Metrics)

**Fix:** Added four new sections to `docs/CLI_REFERENCE.md`.  
**File:** `docs/CLI_REFERENCE.md`

---

### BUG-30: Sidebar method name font too small for readability

**Source:** Dashboard — `TestSidebar.vue`  
**Symptom:** Method names in the test detail sidebar rendered at `.62rem` —
difficult to read at standard screen resolutions.  
**Fix:** Increased to `.68rem`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestSidebar.vue`

---

## Areas Tested With No Bugs Found

### Integration Tests (9 ITs)
All 9 ITs pass including the `cross-module-tracking` IT which verifies:
- Cross-module class-id-map correctness (`ServiceTest → Library`)
- Inner class edges (`Library$Counter`, `Library$Counter$Snapshot`)
- Anonymous class edges (`Library$1`, `Library$2`)
- Per-module hash isolation

### Error Recovery Scenarios
All tested scenarios produced clear, actionable error messages (no NPEs,
no silent failures, no cryptic stack traces):

| Scenario | Result |
|----------|--------|
| Missing `test-dependencies.lz4` | Falls back to learn mode with clear warning |
| Truncated/corrupt index (8 bytes) | Detects corruption, triggers re-learn |
| `test-order:select` with no prior learn | Clear "run learn first" message |
| Inside non-git directory | Falls back to hash-based detection with warning |
| Stale `.class-id-map.bin.lock` | Harmless — OS FileLock is process-scoped, auto-released |
| `test-order:diagnose` healthy state | Clean report with no spurious warnings |
| `test-order:diagnose` broken state | Actionable diagnosis output |

### Dashboard Features Verified Working
- Tests tab: filter, sort, multi-select, CSV export, pagination, cluster/package view
- Test detail: score breakdown, run history, dep graph, method-level graph focus, similar tests, coverage siblings
- Analytics tab: health score, APFD charts, coverage treemap, rank heatmap, run history browser, time budget optimizer, APFD factor attribution
- Weights tab: sliders, presets, rank comparison table, simulated APFD
- Command palette (⌘K): test search, `>` commands mode with all filter/nav/action commands
- Legend/help panel: color codes, keyboard shortcuts, metrics explained
- ML tab: accessible via ⌘K, correctly shows empty state when no ML data
- "abc"/"pkg…" abbreviation toggle: works correctly

### Third-Party Log Audit
Logs from `spring-petclinic`, `jsoup`, `gson`, `commons-text`, `jackson-databind`
reviewed. All stack traces were from pre-fix plugin versions or external test
failures, not current plugin bugs.

---

## Outstanding Items

### Task #34: mvnd (Maven Daemon) Support
- Verify `CollectorLifecycleParticipant` registers correctly with mvnd
- File-lock mechanism compatibility with daemon process lifecycle
- Run demo-shop build with mvnd to exercise full learn → order → dashboard cycle

### Task #27: Live Third-Party Runs
Not yet executed (learn → order → select → tiered → bugs pipeline on
spring-petclinic, jsoup, commons-text). Deprioritized after extensive IT
coverage and error-recovery testing confirmed the core pipeline is sound.

---

### BUG-31: `third_party_test_plan.sh` used old goal name `select` (renamed to `affected`)

**Source:** `scripts/third_party_test_plan.sh`  
**Symptom:** All 4 Maven `select`/`bugs`/`full` phases fail with:
> `Could not find goal 'select' in plugin me.bechberger:test-order-maven-plugin:0.0.1-SNAPSHOT`
**Root cause:** `SelectMojo` was renamed from `@Mojo(name="select")` to
`@Mojo(name="affected")` but the test script was not updated.  
**Fix:** Replaced all 4 occurrences of `me.bechberger:test-order-maven-plugin:select`
with `me.bechberger:test-order-maven-plugin:affected` in the script.  
**Verified:** `bugs jsoup` now reports "Bug caught in top-3 selected tests!";
`select jsoup` completes with SELECT + run-remaining in 14s.

---

### BUG-32: `recordUsageIdFast` / `recordMemberUsageIdFast` NPE when `s.target` is null

**Source:** `test-order-agent/src/main/java/me/bechberger/testorder/agent/runtime/UsageStore.java`  
**Symptom:** `NullPointerException: Cannot invoke "BitsetTracker.recordMember(int)" because "s.target" is null` — surfaced during mockito full workflow learn phase.  
**Root cause:** `RecordingState.target` is `(methodTracker != null) ? methodTracker : classTracker`. Both can be null simultaneously when `endTestMethod()` is called at line 326 with `activeTrackers.test == null` (e.g., during concurrent state transitions or test lifecycle edge cases). The null guard at line 228 checked only `s == null`, not `s.target == null`.  
**Fix:** Added `|| s.target == null` guard to both hot-path methods:
```java
if (s == null || s.target == null) return;
```
**File:** `test-order-agent/src/main/java/me/bechberger/testorder/agent/runtime/UsageStore.java` (lines 213–215, 227–230)

---

### BUG-33: Dashboard FFP chart and run-detail panel showed 0-based position

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The "First failure position" timeline chart plotted `0` for a run where the first test failed (should show `1`). The run history browser panel also showed "First failure at position **0**" in the same scenario.  
**Root cause:** BUG-25's fix only corrected the "Avg 1st Fail Pos" KPI stat (line 1737). Two other display sites in the same file still rendered the raw 0-based `firstFailurePosition`:
- Timeline chart dataset at line 261: `r.firstFailurePosition`
- Run-detail span at line 2336: `{{ selectedRun.firstFailurePosition }}`  
**Fix:** Applied `+ 1` to both sites:
- Chart data: `r.firstFailurePosition >= 0 ? r.firstFailurePosition + 1 : 0`
- Run detail: `{{ selectedRun.firstFailurePosition + 1 }}`  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

## Third-Party Live Run Results (continued)

### mockito (Gradle) — learn/order pass; select/bugs blocked by Java 25 compat

mockito's Gradle Kotlin DSL (Gradle 8.14.x) embeds a Kotlin compiler that cannot parse Java version `25.0.3` (`JavaVersion.parse` fails with `IllegalArgumentException: 25.0.3`). This causes ALL tasks that compile Kotlin build scripts (including `testOrderSelect`, `testOrderAffected`) to fail. This is a **third-party Kotlin/Gradle compatibility issue** unrelated to the test-order plugin. The learn and test phases pass successfully.

- learn: ✓ passed (35 tests collected)
- order: ✓ passed (35 tests reordered)
- select: ✗ blocked by Kotlin compiler/Java 25 incompatibility (not a test-order bug)
- bugs: ✗ blocked by same incompatibility

---

### BUG-33: Dashboard FFP chart and run-detail panel showed 0-based position

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The "First failure position" timeline chart plotted position `0` for a run where the first test failed (should show `1`). The run history browser detail panel also showed "First failure at position **0**".  
**Root cause:** BUG-25's fix only corrected the "Avg 1st Fail Pos" KPI stat at line 1737. Two other display sites still rendered raw 0-based `firstFailurePosition`: the timeline chart dataset (line 261) and the run-detail span (line 2336).  
**Fix:** Added `+ 1` to both remaining sites.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-34: Rank heatmap "click to inspect run" selected the wrong run

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Clicking a column header in the rank heatmap to inspect a run opened the detail panel for the *mirror* run (e.g., clicking the newest run opened the oldest run's detail).  
**Root cause:** `rankHeatmap.runs` is `[...d.runs].reverse()` (newest-first for left→right display), but `selectRun(ri)` used `ri` directly as an index into `d.runs` (oldest-first). The `ri`-th column in the heatmap corresponds to `d.runs[d.runs.length - 1 - ri]`, not `d.runs[ri]`. The highlight comparison `selectedRunIdx === ri` had the same inversion so the selected column was also highlighted incorrectly.  
**Fix:** Changed both the `@click` handler and the `:style` highlight condition:
```js
// Before (wrong):
@click="selectRun(ri)"
selectedRunIdx === ri

// After (correct):
@click="selectRun(d.runs.length - 1 - ri)"
selectedRunIdx === (d.runs.length - 1 - ri)
```
Also corrected the misleading comment `// oldest-first` → `// newest-first for left→right display`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-35: "Last 5 / Last 10" time filter showed oldest runs instead of newest

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** When switching the time-range selector to "Last 5" or "Last 10", the timeline charts displayed the 5 (or 10) oldest recorded runs instead of the 5 (or 10) most recent runs.  
**Root cause:** `filteredRuns` used `d.runs.slice(0, N)` which takes the first (oldest) N elements from the ascending-sorted `d.runs` array. The correct operation is `d.runs.slice(-N)` to take the last (newest) N elements.  
**Fix:**
```js
// Before:
if (timeRangeOpt.value === 'last5') return d.runs.slice(0, 5)
if (timeRangeOpt.value === 'last10') return d.runs.slice(0, 10)

// After:
if (timeRangeOpt.value === 'last5') return d.runs.slice(-5)
if (timeRangeOpt.value === 'last10') return d.runs.slice(-10)
```
Also updated `chartIdxToRunIdx` with a proper `runOffset` to correctly map filtered chart indices back to `d.runs` indices regardless of slice position.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-36: Time budget optimizer used oldest failing run for APFD estimate

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The time budget optimizer's APFD estimate was based on the oldest run with failures instead of the latest.  
**Root cause:** `d.runs.find(r => r.totalFailures > 0)` returns the first (oldest) match from the ascending-sorted array.  
**Fix:** `[...d.runs].reverse().find(r => r.totalFailures > 0)` — searches newest-first.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-37: Flakiness trend "rising/falling" label was inverted

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The flakiness trend chart incorrectly labeled a worsening trend as "falling" and an improving trend as "rising".  
**Root cause:** `orderedRuns = [...d.runs].reverse()` put `runs[0]` (oldest) at the *end* of the array, so the slope calculation compared the wrong halves.  
**Fix:** Removed `.reverse()` — `d.runs` is already oldest-first, no reversal needed.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-38: Rising-risk test detection iterated runs newest→oldest, inverting regression slope

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Tests that had recently started failing were incorrectly classified as "decreasing risk" and vice versa.  
**Root cause:** The `risingRiskTests` loop iterated `i = d.runs.length - 1` downward (newest first), assigning regression coefficients based on reversed time order.  
**Fix:** Changed to `i = 0` upward so `i` increases with time.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-39: APFD trend insight compared oldest 3 runs instead of newest

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The "APFD trend" insight text said "improving" when the latest runs showed worsening APFD.  
**Root cause:** `apfds.slice(0, 3)` (recent) vs `apfds.slice(3, 6)` (prior) — both slices taken from the start (oldest) of the array.  
**Fix:** `apfds.slice(-3)` (recent 3) vs `apfds.slice(-6, -3)` (prior 3).  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-40: Health arc narrative showed oldest APFD as "latest", inverted clean-streak count

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Health arc said "latest APFD: 0.XX" where XX was from the oldest run. Clean-streak counter grew from the oldest runs upward (should count unbroken passes from most recent backward).  
**Root cause:** `apfds.slice(0, 3)` used for recent average; `apfds.slice(-3)` used for early average (exactly backwards). Clean-streak loop started at `i=0` (oldest) instead of `i=length-1` (newest). `apfds[0]` used as "latest".  
**Fix:** Swapped slices; reversed clean-streak iteration direction; changed `apfds[0]` to `apfds[apfds.length - 1]`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-41: `latestRun` returned oldest run

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** Any KPI card or insight using `latestRun` reflected the oldest recorded run's data.  
**Root cause:** `latestRun = runs[0]` — `runs` is sorted ascending, so `runs[0]` is oldest.  
**Fix:** `latestRun = runs[runs.length - 1]`.  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts`

---

### BUG-42: Run diff compared 2 oldest runs instead of 2 newest

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** The "Run diff" card on the Analytics tab always showed the difference between the two oldest runs.  
**Root cause:** `prev = runs[1]`, `curr = runs[0]` — should be `prev = runs[n-2]`, `curr = runs[n-1]`.  
**Fix:** `prev = runs[runs.length - 2]`, `curr = runs[runs.length - 1]`.  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts`

---

### BUG-43: Simulated APFD used oldest run outcomes instead of newest

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** The simulated APFD computed with current weights reflected the test outcomes from the oldest run, not the latest.  
**Root cause:** `lastRun = runs[0]`.  
**Fix:** `lastRun = runs[runs.length - 1]`.  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts`

---

### BUG-44: Per-test last-8-run history showed 8 oldest runs, not 8 newest

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** The history dots in the tests table reflected the test's pass/fail status from the oldest 8 runs. The most recent test runs were not visible.  
**Root cause:** `runs.slice(0, 8).reverse()` — `slice(0, 8)` takes the 8 oldest; `.reverse()` just reversed their display order but still showed oldest data.  
**Fix:** `runs.slice(-8)` — takes the 8 most recent runs in chronological (oldest-first) order.  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts`

---

### BUG-45: Rank trend in `TestsTab.vue` was inverted

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** A test that had been improving in rank over recent runs was labeled "worsening" and vice versa.  
**Root cause:** `recent = positions.slice(0, half)` took the oldest half of the rank history; `early = positions.slice(-half)` took the newest half. The labels were applied to the wrong halves.  
**Fix:** Swapped: `recent = positions.slice(-half)`, `early = positions.slice(0, half)`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-46: Score sparkline showed oldest 8 runs; tooltip ordering reversed

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** The score sparkline in the tests table showed the oldest 8 scores. Hovering showed scores in reversed order (newest first in the list = confusing "oldest → newest" label).  
**Root cause:** `scores.slice(0, 8).reverse()` and tooltip `.reverse().join()`.  
**Fix:** `scores.slice(-8)` for the sparkline; removed `.reverse()` before `.join()` in the tooltip.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-47: `firstSeenMap` found newest occurrence instead of first (oldest)

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** The "first seen: run #N" badge always showed the most recent run a test appeared in, not its actual debut run.  
**Root cause:** Loop iterated `i = d.runs.length - 1` downward (newest first) and used `m.set(name, i)` unconditionally — so older occurrences overwrote newer ones, and the final stored value was the oldest appearance. Wait — actually iterating newest→oldest and overwriting means the final value = *oldest* appearance. But the `selectedFirstSeen` timestamp lookup `d.runs[d.runs.length - 1 - idx]` then inverted the index, showing the wrong run's timestamp.  
**Root cause (corrected):** The index stored by the newest→oldest iteration was an inverted index, then double-inverted in `selectedFirstSeen` giving the wrong run.  
**Fix:** Changed iteration to `i = 0` upward (oldest first) with `if (!m.has(name)) m.set(name, i)` — records only the first (oldest) occurrence. Fixed `selectedFirstSeen` to use `d.runs[idx]` directly.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-48: `lastFailureInfo` in `KpiRow.vue` found oldest failure instead of most recent

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** The "last failure" KPI showed an old failure date even when a recent run had failures. The tooltip run number was also wrong (using `d.runs.length - runsAgo` formula producing the wrong run number).  
**Root cause:** Loop started at `i = 0` (oldest run) and broke on first failure found — returning the oldest, not the most recent failure. Tooltip run number formula also incorrect.  
**Fix:** Reversed loop to start at `i = d.runs.length - 1` (newest). Fixed tooltip to `runsAgo + 1`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-49: Run detail `prevRunScoreMap` / `runDiff` referenced newer run instead of older run

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The score-delta arrows in the run detail panel pointed in the wrong direction (test scored higher than "previous" run when it actually scored lower).  
**Root cause:** `d.runs[selectedRunIdx + 1]` used as "previous run" — since `d.runs` is ascending, `idx + 1` is a *newer* run, not older.  
**Fix:** Changed to `d.runs[selectedRunIdx - 1]` with updated guards (`<= 0` instead of `>= length - 1`).  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-50: Run detail nav buttons "‹ older / › newer" had `selectRun` calls swapped

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Clicking "‹ older" navigated to a newer run; clicking "› newer" navigated to an older run.  
**Root cause:** In `d.runs` ascending order, "older" = lower index (`selectedRunIdx - 1`) and "newer" = higher index (`selectedRunIdx + 1`). The button implementations were swapped: older button called `selectRun(selectedRunIdx + 1)` and newer button called `selectRun(selectedRunIdx - 1)`.  
**Fix:** Swapped the `selectRun` arguments and updated both `:disabled` guards. Also fixed the APFD delta comparison that used the wrong reference run.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-51: Sidebar run-dot tooltip computed wrong run number

**Source:** Dashboard — `TestSidebar.vue`  
**Symptom:** Hovering a pass/fail dot in the sidebar showed "FAILED in run #10" for the newest run (should be run #1) with 10 total runs.  
**Root cause:** Formula `Math.max(0, d.runs.length - Math.min(5, last8.length)) + hi + 1` computed ascending run numbers (1 = oldest), but the convention is run #1 = newest. With 10 runs and `hi=4` (newest dot): `10 - 5 + 4 + 1 = 10` instead of `1`.  
**Fix:** `Math.min(5, last8.length) - hi` — with `hi=0` (oldest dot) = `5`; `hi=4` (newest dot) = `1`. ✓  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestSidebar.vue`

---

### BUG-52: History dot tooltip in `TestsTab.vue` table used wrong run number formula

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** The per-test history dot tooltip in the main tests table showed ascending run numbers (oldest = 1) instead of descending (newest = 1).  
**Root cause:** Same incorrect formula as BUG-51: `Math.max(0, d.runs.length - last8.length) + hi + 1` computes `d.runs` index + 1 (ascending from oldest), not a descending run number.  
**Fix:** `d.testHistoryMap.value.get(t.name)!.last8.length - hi` — `hi=0` (oldest dot) = `last8.length`; `hi=last8.length-1` (newest dot) = `1`. ✓  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-53: `selectedFirstSeen.runNum` used `idx + 1` (ascending) instead of `totalRuns - idx` (descending)

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** "First seen: run #1" for a test that debuted in the very oldest run (should show run #N where N = total runs).  
**Root cause:** `runNum = idx + 1` where `idx` is the oldest-first `d.runs` index. `idx=0` (oldest run) → `runNum=1`, but run #1 = newest by convention.  
**Fix:** `runNum = totalRuns - idx` — `idx=0` (oldest) → `runNum = totalRuns`; `idx=totalRuns-1` (newest) → `runNum = 1`. ✓  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-54: "Last failed" timestamp showed oldest failure instead of most recent

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** The "last failed" stat in the test detail panel showed an old failure date even when the test had failed more recently.  
**Root cause:** `d.testOutcomes.value.filter(o => o.present && o.failed)[0]?.ts` — `.filter()[0]` returns the first (oldest) match since `testOutcomes` is indexed oldest-first.  
**Fix:** `.filter(...).at(-1)?.ts` — returns the last (newest/most recent) failure.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-55: Test detail charts (pass/fail + score trend) displayed newest→oldest instead of oldest→newest

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** The pass/fail bar chart and score trend line chart in the test detail panel had the time axis reversed: newest runs appeared on the left, oldest on the right.  
**Root cause:** `const chronRuns = [...d.runs].reverse()` — `d.runs` is already oldest-first; reversing it makes it newest-first. The comment incorrectly said "oldest-first for charts".  
**Fix:** `const chronRuns = d.runs` (and same for `chronRuns2`) — use the already-chronological ascending array directly.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/TestsTab.vue`

---

### BUG-56: `KpiRow` APFD trend delta compared oldest 2 runs instead of newest 2

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** The APFD trend arrow (↑/↓/→) next to the Avg APFD KPI showed the wrong direction — if the most recent runs showed improvement, the arrow still pointed down, based on the oldest pair of runs.  
**Root cause:** `prev = d.runs[1].apfd`, `curr = d.runs[0].apfd` — both indexing from the start (oldest) of the ascending `d.runs` array.  
**Fix:** `prev = d.runs[d.runs.length - 2].apfd`, `curr = d.runs[d.runs.length - 1].apfd`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-57: `KpiRow` history browser default showed oldest run data

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** On the Runs KPI history browser, the "latest" state showed pass/fail and APFD data from the oldest recorded run, not the most recent.  
**Root cause:** `currentHistIdx` defaults to `d.runs.length - 1` (max sparkBars index = oldest bar after `.reverse()`), so `historyRun = d.runs[d.runs.length - 1 - (length-1)] = d.runs[0]` = oldest run.  
**Fix:** `currentHistIdx` defaults to `0` (newest bar in reversed sparkBars). `isLive` checks for `historyIdx === 0` instead of `d.runs.length - 1`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-58: `KpiRow` current pass/fail streak computed from oldest run instead of newest

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** The "Streak" KPI showed "3 consecutive passes" when the most recent run had a failure (the 3 oldest runs had passed).  
**Root cause:** `first = d.runs[0]` (oldest) with `for i=1 upward` iterating oldest-to-newest. The streak should start from the most recent run.  
**Fix:** `first = d.runs[d.runs.length - 1]` with `for i=d.runs.length-2 downward`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-59: `KpiRow` "«" button navigated to newest run instead of oldest

**Source:** Dashboard — `KpiRow.vue`  
**Symptom:** Clicking "«" (supposed to jump to oldest run) jumped to the newest run instead.  
**Root cause:** `goToRun(0)` — in the reversed sparkBars array, `i=0` = newest run. The "oldest" destination should be `d.runs.length - 1`.  
**Fix:** Changed `«` to `goToRun(d.runs.length - 1)` with `:disabled="currentHistIdx === d.runs.length - 1"`. Also corrected nav button directions: `‹` now moves toward older runs, `›` toward newer.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/KpiRow.vue`

---

### BUG-60: Analytics health timeline bar rendered newest-left despite "oldest-left" comment

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The compact pass/fail timeline bar below the "Select a Run" heading showed the newest run on the far left and oldest on the far right — opposite to its "Oldest left, newest right" tooltip description.  
**Root cause:** `v-for="(r, i) in [...d.runs].reverse()"` — reversing an ascending array gives newest-first, so `i=0` (leftmost) = newest. The `@click` and highlight expressions used `d.runs.length - 1 - i` to compensate, but the visual order contradicted the label.  
**Fix:** Changed to `v-for="(r, i) in d.runs"` (oldest-first directly), `selectedRunIdx === i`, `@click="selectRun(i)"`, run # = `d.runs.length - i`. Removes the reversal entirely.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-61: First-failure heatmap showed 12 oldest failing runs instead of 12 newest

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** The "First-Failure Detection" heatmap showed only old failing runs — recent regressions were absent.  
**Root cause:** `failingRuns.slice(0, 12)` takes the 12 oldest failing runs from the ascending-sorted array.  
**Fix:** `failingRuns.slice(-12)` — takes the 12 most recent failing runs. The subsequent `.reverse()` correctly puts them in chronological (oldest-left) order.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`


---

### BUG-62: `speedRatioHistoryMap` iterated runs newest-first, building speed history in wrong order

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Speed trend sparklines in the run detail table showed incorrect trends — a test that was progressively getting slower would show a flat or rising line.  
**Root cause:** `speedRatioHistoryMap` loop used `for (let i = d.runs.length - 1; i >= 0; i--)` (newest-first iteration), so each test's speed ratio array was built in reverse-chronological order, causing the sparkline to chart newest→oldest.  
**Fix:** Changed to `for (let i = 0; i < d.runs.length; i++)` — iterates oldest-first, building the array in correct chronological order.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue`

---

### BUG-63: "Most Unreliable Tests" history dot tooltip used wrong (ascending) run number formula

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** In the "Most Unreliable Tests" table's history dot column, hovering over a dot showed the wrong run number. `hi=0` (oldest dot) showed run #`(d.runs.length - last8.length + 1)` instead of run #`last8.length`.  
**Root cause:** Same wrong formula as BUG-51 and BUG-52: `Math.max(0, d.runs.length - Math.min(8, last8.length)) + hi + 1` computes ascending run numbers, but the convention is run #1 = newest.  
**Fix:** `Math.min(8, last8.length) - hi` — hi=0 (oldest dot) → run #`last8.length`; hi=last8.length-1 (newest dot) → run #1.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 2193


---

### BUG-64: Run diff title "Changes vs Run #X (previous)" showed wrong run number (off by 2)

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** When inspecting a run, the diff header showed "Changes vs Run #X (previous)" where X was 2 too low. E.g., with 10 runs and run #5 selected (idx=5), the header showed "Changes vs Run #4" instead of "Changes vs Run #6".  
**Root cause:** `d.runs.length - (selectedRunIdx! + 1)` expands to `d.runs.length - selectedRunIdx - 1`. The previous (older) run's number should be `d.runs.length - (selectedRunIdx - 1) = d.runs.length - selectedRunIdx + 1`.  
**Fix:** Changed to `d.runs.length - selectedRunIdx! + 1`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 2376

---

### BUG-65: `testHistoryMap` O(tests × runs × outcomes) `.find()` causing quadratic slowdown

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** With many tests and runs, the "Tests" and "Analytics" tabs become slow to render because `testHistoryMap` performs an O(outcomes) linear scan *per test per run*.  
**Root cause:** For each test name `t.name`, the code called `(r.outcomes || []).find(o => o.testClass === t.name)` inside a nested loop over all runs. With 1 000 tests × 100 runs × 1 000 outcomes/run = 100 M iterations.  
**Fix:** Pre-build a `Map<testClass, outcome>` for each run before the test loop, reducing the lookup to O(1). Total cost: O(runs × outcomes + tests × runs) instead of O(tests × runs × outcomes).  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts` lines 571-589

---

### BUG-66: `rankHeatmap` re-sorted outcomes per test per run (O(tests×runs×outcomes×log))

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Rank position heatmap is slow to compute with large test suites and many runs.  
**Root cause:** `rankHeatmap` called `[...r.outcomes].sort(...)` and `sorted.findIndex(...)` inside `d.tests.map()`, repeating an O(outcomes×log(outcomes)) sort for every test (1000 tests × 100 runs × sort(1000) = 100M comparisons).  
**Fix:** Pre-build `Map<testClass, rank>` for each run before the per-test loop (same pattern as `testRankHistoryMap` in `useDashboard.ts`). Reduces to O(runs×outcomes×log + tests×runs).  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 693


---

### BUG-67: `flakinessTimeline` O(tests × runs × outcomes) `.find()` causing quadratic slowdown

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Flakiness timeline panel is slow to compute with large test suites and many runs.  
**Root cause:** `flakinessTimeline` called `(r.outcomes || []).find(o => o.testClass === t.name)` inside a loop over all tests for each run — O(tests × runs × outcomes).  
**Fix:** Pre-build `Map<testClass, failed>` for each run before the per-test loop. Also removed unused `WINDOW = 5` constant.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 864

---

### BUG-68: Operator precedence bug `?? 0 > 0` instead of `(?? 0) > 0` in retirement filter

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** No visible symptom today, but the code was fragile and misleading.  
**Root cause:** `if (failCount.get(t.name) ?? 0 > 0)` — due to `??` having lower precedence than `>`, this parsed as `if (failCount.get(t.name) ?? (0 > 0))` = `if (failCount.get(t.name) ?? false)`. Happened to work correctly only because `failCount` never stores 0 (entries only set when `o.failed`). If initialization changed, tests that had 0 logged failures would incorrectly pass the filter.  
**Fix:** Added explicit parentheses: `if ((failCount.get(t.name) ?? 0) > 0)`.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 1438

---

### BUG-69: `flakyTests`, `firstFailHeatmap`, and template `d.tests.find()` O(n²) patterns

**Source:** Dashboard — `useDashboard.ts`, `AnalyticsTab.vue`  
**Symptom:** Dashboard re-renders are slow with large test suites (100+ tests, 50+ runs).  
**Root cause:** Multiple remaining O(tests × runs × outcomes) `.find()` patterns:
  1. `flakyTests` in `useDashboard.ts`: iterated tests × runs, called `.find()` per run per test
  2. `firstFailHeatmap` in `AnalyticsTab.vue`: called `d.tests.find(t => t.name === n)` 4× per failed test per run
  3. Template `v-for` over run outcomes: called `d.tests.find(...)` 4× per rendered row for rank-delta column
  4. Template `firstFailHeatmap` cell matrix: called `.some(o => o.testClass === t.name)` per cell
**Fix:**
  - `flakyTests`: restructured to iterate runs × outcomes, build pass/fail Maps, then check per test
  - `firstFailHeatmap`: pre-built `Map<testClass, rank>` from `d.tests`; pre-built per-run failed Sets
  - `useDashboard.ts`: added `testsByName: computed Map<string, TestEntry>` to replace per-render `.find()` calls
  - Template: replaced `d.tests.find(t => t.name === o.testClass)` with `d.testsByName.value.get(o.testClass)`
**Files:** `useDashboard.ts` lines 241, 249-268, 920; `AnalyticsTab.vue` lines 1360-1422, 2538-2540

---

### BUG-70: `DependencyMap.putDirect()` failed to invalidate `depFrequencies` lazy cache

**Source:** Core — `DependencyMap.java`  
**Symptom:** If `putDirect()` was called on a map that already had `depFrequencies` computed, subsequent `idf()` calls would return stale values reflecting the pre-modification set of dependencies.  
**Root cause:** `putDirect()` reset `invertedIndex = null` but not `depFrequencies = null`. The public `put()` method correctly resets both.  
**Fix:** Added `depFrequencies = null;` to `putDirect()`.  
**File:** `test-order-core/.../DependencyMap.java` line 235

---

### BUG-71: `aggregateFromDepsDirectory` bypassed cache invalidation via direct `dependencies.put()`

**Source:** Core — `DependencyMap.java`  
**Symptom:** After aggregation, if the returned map were used for `idf()` or `getAffectedTests()`, it would use stale caches because the merge loop wrote directly to `map.dependencies` without going through `map.put()`.  
**Root cause:** Lines 1664-1673 called `map.dependencies.put()` directly (bypassing cache invalidation), then called `map.save()` without first resetting `invertedIndex` and `depFrequencies`.  
**Fix:** Added `map.invertedIndex = null; map.depFrequencies = null;` before `map.save()`.  
**File:** `test-order-core/.../DependencyMap.java` before `map.save(indexFile)` call

---

### BUG-72: `speedTrendSvg` color was inverted (green for slowing trend, orange for speeding up)

**Source:** Dashboard — `AnalyticsTab.vue`  
**Symptom:** Speed trend sparklines showed green when a test was getting slower and orange when getting faster — the opposite of intuition.  
**Root cause:** `TestScorer.speedRatio()` returns negative values for fast tests and positive for slow tests (it's `log2(duration/median)`, clamped to [-1,1]). The comment "Positive speedRatio = fast" was wrong. The trend color condition was `trend > 0.05 → green` (positive trend = becoming slower = should be orange) and `trend < -0.05 → orange` (negative trend = becoming faster = should be green).  
**Fix:** Swapped color conditions: `trend < -0.05 → green` (getting faster); `trend > 0.05 → orange` (getting slower). Updated comment.  
**File:** `test-order-dashboard/src/main/dashboard/src/components/AnalyticsTab.vue` line 161

---

### BUG-73: `testsByName` missing from `DashboardState` interface

**Source:** Dashboard — `useDashboard.ts`  
**Symptom:** `d.testsByName` was computed and returned from `useDashboard()` but not declared in the exported `DashboardState` interface. Any code destructuring or typing `d` as `DashboardState` silently lost type safety for `testsByName`. Template usages in `AnalyticsTab.vue` (lines 2211, 2538) accessed `d.testsByName.value.get(...)` without TypeScript validation.  
**Root cause:** BUG-69 fix added `testsByName` as a computed property and returned it, but the `DashboardState` interface at the top of the file was not updated to include it.  
**Fix:** Added `testsByName: ComputedRef<Map<string, TestEntry>>` to the `DashboardState` interface before `testHistoryMap`.  
**File:** `test-order-dashboard/src/main/dashboard/src/composables/useDashboard.ts` line 60

---

### BUG-74: Run history tooltip convention — investigated, not a bug

**Source:** Dashboard — `TestsTab.vue`  
**Symptom:** Investigated whether "Run #1" for the newest run was wrong.  
**Finding:** The entire dashboard uses Run #1 = newest, Run #N = oldest — confirmed in `AnalyticsTab.vue` lines 1088, 2301, 2334, and `TestsTab.vue` line 1403. The original `(i+1)` formula at line 1368 was correct.  
**Action:** Reverted incorrect BUG-74 fix. Not a bug.

---

### BUG-75: `MutationAnalysisOperation` incorrectly normalized inner-class `$` to `.`

**Source:** Core — `MutationAnalysisOperation.java`  
**Symptom:** When PIT reports a killing test as `com.example.OuterTest$InnerTest`, the code converted it to `com.example.OuterTest.InnerTest` before looking it up in `knownTestClasses`. But the dependency map stores inner-class test names with `$` (matching the JVM binary name format), so the lookup always failed. The test never got credited with killing any mutants, causing wrong kill rates for inner-class test fixtures.  
**Root cause:** Line 440 applied `testClass.replace('$', '.')` with a comment saying "we use Outer.Inner" — but that was wrong; the dep map uses `Outer$Inner`.  
**Fix:** Removed the `replace('$', '.')` call. PIT's `$`-form already matches the dep-map keys directly.  
**File:** `test-order-core/.../ops/MutationAnalysisOperation.java` lines 438-440

---

### BUG-76: `ClassOrderingEngine` diversity sort passed null to `jaccardDistance`

**Source:** Core — `ClassOrderingEngine.java`  
**Symptom:** Potential NPE in `TestSelector.jaccardDistance()` when a test class has no dependency data — `depMap.get()` could return null in pre-`getOrDefault` versions, and the depsCache stored the raw null. `jaccardDistance(null, coveredDeps)` calls `null.isEmpty()` → NPE.  
**Root cause:** `depsCache.put(className, depMap.get(className))` at line 184 and 268 stored null if `depMap.get()` returned null. `jaccardDistance(Set<String> a, ...)` calls `a.isEmpty()` at line 255 without null check.  
**Fix:** Added `d != null ? d : Set.of()` fallback in both diversity sort loops in `ClassOrderingEngine.java`.  
**Files:** `test-order-core/.../ClassOrderingEngine.java` lines 183-185 and 267-269

### BUG-77: Δ Score column condition inverted in run detail table

**Source:** Dashboard UI — `AnalyticsTab.vue`  
**Symptom:** The "Δ Score" column in the run detail table was hidden for the newest run (where it's most useful — shows how scores changed compared to the prior run) and shown for the oldest run (where it's empty, since there's no older run to compare against).  
**Root cause:** The `v-if` condition was `selectedRunIdx! < d.runs.length - 1`, which evaluates to `false` for the newest run (index `d.runs.length - 1`) and `true` for the oldest run (index 0). Since `prevRunScoreMap` is built from `d.runs[selectedRunIdx - 1]` (the older run), the column should be visible when `selectedRunIdx > 0` (there exists an older run to compare to).  
**Fix:** Changed `v-if="selectedRunIdx! < d.runs.length - 1"` to `v-if="selectedRunIdx! > 0"` on both the `<th>` header and the `<td>` data cell in `AnalyticsTab.vue`.  
**Files:** `test-order-dashboard/.../AnalyticsTab.vue` lines 2492, 2517

### BUG-78: `DashboardGenerator` serialized null SA file path as the string `"null"`

**Source:** Core — `DashboardGenerator.java`  
**Symptom:** When a file-summary entry from static analysis data is missing its `path` field (null in the raw JSON map), the generated dashboard JSON would contain the literal string `"null"` as the path, rather than JSON null or an omitted field.  
**Root cause:** `String.valueOf(raw.get("path"))` at line 519 converts Java null to the string `"null"`. This is a difference from `raw.get("path").toString()` (which throws NPE) and proper null handling.  
**Fix:** Changed to `raw.get("path") instanceof String s ? s : null` to preserve null correctly.  
**Files:** `test-order-core/.../DashboardGenerator.java` line 519

### BUG-79: `TestOrderState` wrote empty method duration maps when all classes pruned

**Source:** Core — `TestOrderState.java`  
**Symptom:** When saving state after all test classes have been pruned from `methodDurations` or `methodDurationVariances` (e.g., first save after all old classes retired), the serializer wrote an empty `{}` object for those keys instead of omitting them.  
**Root cause:** Lines 927 and 942 called `root.put("methodDurations", mdMap)` / `root.put("methodDurationVariances", mdvMap)` unconditionally, even when all class entries were filtered out by the `activeClasses` pruning loop inside. The class-level `durationVariances` key (line 893-895) already had the correct `if (!prunedVariances.isEmpty())` guard, but the method-level variants were missing it.  
**Fix:** Added `if (!mdMap.isEmpty())` / `if (!mdvMap.isEmpty())` guards around `root.put(...)` for both method duration maps, consistent with the existing `durationVariances` pattern.  
**Files:** `test-order-core/.../TestOrderState.java` lines 927 and 942

### BUG-80: Mutation score card showed tautological "100%"; real score never persisted

**Source:** Dashboard UI — `MutationTab.vue` + Core — `TestOrderState.java` / `DashboardGenerator.java`  
**Symptom:** The "Overall kill share" card in the Mutations tab always displayed ~100%. The real mutation score (e.g., "73% of mutants killed") was never shown anywhere in the dashboard.  
**Root cause:** `MutationTab.vue` computed `overallRate` as `ts.reduce((acc, t) => acc + t.killRate, 0)` where each `killRate` is `testKilled / totalKilled`. Since PIT attributes each killed mutant to exactly one test, the sum is always exactly 1.0 (100%), which is a tautology. Meanwhile `TestOrderState` only persisted the per-test kill-rate fractions, not the global `totalKilled` / `totalMutants` counters, so `DashboardGenerator` had no way to emit the real mutation score.  
**Fix:**  
1. `TestOrderState`: Added `mutationTotalMutants` and `mutationTotalKilled` fields with `setMutationTotals()` setter, persisted/loaded alongside `killRates`.  
2. `MutationAnalysisOperation`: Calls `state.setMutationTotals(totalMutants, totalKilled)` when saving kill rates.  
3. `DashboardGenerator`: Emits `totalMutants`, `totalKilled`, `overallKillRate` (= `totalKilled/totalMutants`) in the mutation section.  
4. `types.ts`: Added those three optional fields to `MutationData` interface.  
5. `MutationTab.vue`: Replaced `overallRate` with `overallKillRate` (from server data), renamed card label to "Mutation score", added `totalKilled/totalMutants` sub-label.  
**Files:** `TestOrderState.java`, `MutationAnalysisOperation.java`, `DashboardGenerator.java`, `types.ts`, `MutationTab.vue`

### BUG-81: `CollectorLifecycleParticipant` session-drain used non-normalized path, causing collector lookup miss

**Source:** Maven plugin — `CollectorLifecycleParticipant.java`  
**Symptom:** In a multi-JVM or forked build, the session-end drain loop could fail to find and stop IndexCollectorServer instances, leaving them running and causing merged dependency data to not be written to disk. Data loss: test-class dependency edges from the failed-to-drain collector would be missing from the index.  
**Root cause:** `AbstractTestOrderMojo.startCollector()` stores collectors in `activeCollectors` keyed by `indexFilePath.toAbsolutePath().normalize()` (line 990). The `registerCollectorInSession()` also serializes the path as `toAbsolutePath().normalize()` into the Maven session properties (line 1017). However, the drain loop in `CollectorLifecycleParticipant` reconstructed the path with `Path.of(entry.substring(colon+1))` (line 597) — no `.normalize()` call — so the `ConcurrentHashMap.remove()` could miss the entry if the path string contained any non-canonical segments.  
**Fix:** Added `.normalize()` to the path reconstruction at line 597.  
**Files:** `test-order-maven-plugin/.../CollectorLifecycleParticipant.java` line 597

### BUG-82: `tryReorderReactor` called `getTopLevelProject()` without null check

**Source:** Maven plugin — `CollectorLifecycleParticipant.java`  
**Symptom:** Potential NPE in degenerate Maven sessions (e.g., test fixtures, unusual reactor configs) where `getTopLevelProject()` returns null.  
**Root cause:** `tryReorderReactor()` called `session.getTopLevelProject().getBasedir().toPath()` at line 386 without null-checking `getTopLevelProject()`. The sibling method `prepareReactorClassIdMap()` correctly guards this with `if (top == null || top.getBasedir() == null) return`.  
**Fix:** Added consistent null guard for `getTopLevelProject()` before line 386.  
**Files:** `test-order-maven-plugin/.../CollectorLifecycleParticipant.java` lines 383-387

### BUG-83: `buildId.substring(0, 8)` without length guard

**Source:** Maven plugin — `CollectorLifecycleParticipant.java`  
**Symptom:** `StringIndexOutOfBoundsException` in the run-aggregation log message if `buildId` is shorter than 8 characters.  
**Root cause:** `buildId.substring(0, 8)` at the aggregation log line assumed the ID is always ≥ 8 chars. Build IDs from UUIDs are always 36 chars, but the fallback path (key without `|`) could in theory produce shorter strings.  
**Fix:** Changed to `buildId.length() > 8 ? buildId.substring(0, 8) + "..." : buildId`.  
**Files:** `test-order-maven-plugin/.../CollectorLifecycleParticipant.java` line ~671

### BUG-84: `PersistenceSupport` lost `OverlappingFileLockException` cause after 50 retries

**Source:** Core — `PersistenceSupport.java`  
**Symptom:** If all 50 lock attempts fail with `OverlappingFileLockException`, the thrown `IOException` has the generic message "Could not acquire lock … after 50 attempts" with no cause attached — making it hard to diagnose that the issue was a lock contention loop.  
**Root cause:** The retry loop only saved `IOException` as `lastIo` but not `OverlappingFileLockException` (different exception hierarchy). After 50 overlapping-lock failures the cause was discarded.  
**Fix:** Track `lastOverlap` separately; attach it as the cause with `initCause()` when throwing the timeout error.  
**Files:** `test-order-core/.../PersistenceSupport.java` lines 126-148

### BUG-85: `CiSummaryWriter` GITHUB_REF split without bounds check

**Source:** Core — `CiSummaryWriter.java`  
**Symptom:** `ArrayIndexOutOfBoundsException` when `GITHUB_REF` is set to a malformed pull-request reference (e.g., `refs/pull/` with no PR number). The exception aborts PR comment posting with no useful error message.  
**Root cause:** `ref.split("/")[2]` — `split("/")` on trailing slashes discards empty trailing tokens, so `"refs/pull/".split("/")` yields a 2-element array, making index 2 out-of-bounds. The `startsWith("refs/pull/")` guard ensures the prefix matches but doesn't guarantee there is a PR number segment.  
**Fix:** Added `if (parts.length > 2)` bounds check before accessing `parts[2]`.  
**Files:** `test-order-core/.../CiSummaryWriter.java` line 268

### BUG-86: `StructuralDiff` initializer diff misclassified duplicate-hash additions as REMOVED

**Source:** Core — `StructuralDiff.java`  
**Symptom:** If a class gains a second initializer block with the same body hash as an existing one (e.g., two identical static initializers), the change is classified as REMOVED instead of ADDED. This causes test-order to believe code was deleted when it was actually added, potentially skipping tests that depend on the new initializer.  
**Root cause:** The classification used `!oldHashes.contains(h)` to count `added` — but `List.contains()` returns `true` even when the old list has fewer occurrences than the new. For `old=[A,B]` → `new=[A,A,B]`: the second `A` in new is not detected as added because `oldHashes.contains("A")` is `true`. Both `added=0` and `removed=0`, falling into the `else` (REMOVED) branch despite neither hash being removed. The comment in the code notes "use sorted lists to detect duplicate counts" but the counting logic didn't follow through.  
**Fix:** Replaced `contains()` with frequency-map comparison using `groupingBy(counting())` to correctly count per-hash occurrence changes.  
**Files:** `test-order-core/.../StructuralDiff.java` lines 474-481
