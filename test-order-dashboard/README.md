# Test Order Dashboard

Interactive HTML report for [test-order](../README.md). Served by `mvn test-order:serve` (or opened via `mvn test-order:dashboard`), it gives a full-history view of your test suite's prioritization quality, individual test behaviour, and scoring configuration.

## Quick start

```bash
# Run at least one prioritized test execution first, then:
mvn test-order:serve       # Opens dashboard at a local port (shown in output)
mvn test-order:dashboard   # Writes static HTML to target/test-order-dashboard/
```

The dashboard is self-contained — all data is embedded as JSON; no server required for the static version.

## Tabs

### Tests

The primary view. A prioritized ranked list of all test classes with sortable columns, inline sparklines, and a rich detail panel.

**Overview table columns**

| Column | Description |
|---|---|
| Rank | Current priority position (1 = runs first) |
| Test class | Abbreviated class name; hover for hover card with Javadoc + public methods |
| Score | Composite priority score; click for breakdown modal |
| Score bar | Stacked colour-coded bar showing score component contributions |
| Score history | Mini sparkline of score across the last 8 runs |
| Rank trend | Sparkline of rank position across the last 8 runs |
| Duration | EMA-smoothed execution time; colour-coded fast/slow |
| Duration variance | Spread of measured durations — high variance = unstable timing |
| Run history | Last 8 pass/fail dots (green = pass, red = fail, left = oldest) |
| Confidence | % of runs the test appeared in; low = sparse history |
| Stability | Composite reliability score (0–100) |

**Sidebar** — filter chips (`Fail`, `Flaky`, `New`, `Changed`, `High Var`, `At Risk`, `Watching`), dep-cluster grouping, package group-by, and a minimap scroll navigator with colour-coded rank bands.

**Test detail panel** — opened by clicking a row:

- Score breakdown chart + component table
- Run history squares (oldest→newest, click to inspect a run in Analytics)
- Pass/fail chart and score-over-runs chart
- Run position history (rank per run, clickable)
- Position context strip (neighbours in priority order)
- Similar tests by shared dependency class
- Coverage sibling tests
- Source dependency list with "hot" class highlighting
- Method list

**Keyboard shortcuts**

| Key | Action |
|---|---|
| `j` / `k` | Next / previous test |
| `Esc` | Back to overview |
| `f` | Toggle fail filter |
| `b` | Toggle blame mode (highlight tests linked to changed classes) |
| `⌘K` | Open command palette |

### Analytics

History-wide analysis across all recorded runs.

**Timeline charts** (oldest→newest): APFD, failures, first-failure position, test count.

**Run health bar** — compact timeline of every run as a colour-coded bar segment; click to drill into that run.

**Run detail panel** (click a run chip or health bar segment):

- Pass/fail badge, APFD, comparison to previous run
- Run composition (new / removed / changed tests)
- Run diff: new failures, recoveries, new tests, significant rank changes
- Full test table with rank, score delta, duration sparkline, score composition mini-bar
- Filters: failures-only, name search

**Other panels**:

- Session health summary (trend arc card)
- Unreliable tests table with sparklines
- Rank heatmap (tests × runs, colour = rank position)
- Failure correlation matrix
- First-failure detection heatmap
- Test risk trend (rising failure probability)
- Time budget optimizer (best test subset for a given run time)
- Flakiness timeline (rolling per-test flakiness rate)
- Test longevity cohort breakdown
- Co-failure burst detection
- Coverage efficiency panel (over/under-tested source classes)
- APFD factor attribution breakdown
- APFD improvement recommendations
- Top Impact Source Classes
- Test retirement candidates
- Test execution Gantt timeline

**Two-run comparison mode** — Shift+click a second run chip to enable side-by-side diff.

**Keyboard navigation**: `←` / `→` moves between runs in the detail panel.

### Weights

Tune the five scoring components and instantly see rank changes.

| Component | Controls |
|---|---|
| Failure history (`failScore`) | Weight applied to EMA-decayed past failure signal |
| Change overlap | Weight for dep-class overlap with changed source files |
| Duration bonus | Reward for fast tests |
| Flakiness | Weight applied to flakiness score |
| Coverage novelty | Reward for covering source classes not covered by higher-ranked tests |

**Presets** — buttons for common configurations (balanced, fail-first, speed-first, etc.).

**Show changed only** — hides tests whose rank didn't change.

**Top movers** — summary of tests with the largest rank shift under current weights.

**Score sensitivity curves** — for the selected test, shows how its rank changes as each weight varies.

**Share via URL** — weight configuration is encoded in the URL hash for easy sharing.

## KPI bar

Shown at the top of every tab:

| Metric | Description |
|---|---|
| APFD | Average % of faults detected; 100% = every failing test runs first |
| Failures | Count in the most recent run |
| Streak | Consecutive passing / failing runs; best streak badge |
| Clean since | Runs since last failure, with the failing run number and test names |
| At-risk | Top 3 tests most likely to fail next run |
| Time savings | Estimated time to first failure vs random order |
| Suite Health | Letter grade (A–F) derived from APFD, reliability, flakiness, coverage |

The run history slider (spark bars + arrows) browses past runs; bars show APFD height and failure shading.

## APFD — what it means

**APFD** (Average Percentage of Faults Detected) measures how early failing tests appear in the execution order:

- **100%** — all failing tests run first (perfect)
- **50%** — same as random order (baseline)
- **0%** — all failing tests run last (worst case)

The dashboard tracks APFD per run and shows trends. Higher and more consistent APFD means developers see broken builds faster.

## Development

The dashboard is a Vue 3 SPA built with Vite and TypeScript.

```bash
cd src/main/dashboard
npm install
npm run dev       # Hot-reload dev server (uses embedded fixture data)
npm run build     # Production build → ../resources/dashboard/dist/
npm run typecheck # Type-check only
```

After `npm run build`, run `mvn install -pl test-order-dashboard -am` to package the build into the Maven module so `mvn test-order:serve` picks it up.

### Key files

| File | Role |
|---|---|
| `src/composables/useDashboard.ts` | Central state: loads JSON, exposes all computed properties and actions |
| `src/composables/useCharts.ts` | Chart.js wrapper (`mkChart`, `destroyCharts`, timeline sync plugin) |
| `src/composables/useClassInfo.ts` | Javadoc / method info fetcher with LRU cache |
| `src/components/TestsTab.vue` | Tests tab: overview table + detail panel |
| `src/components/AnalyticsTab.vue` | Analytics tab: timeline, run browser, heatmaps, all analysis panels |
| `src/components/KpiRow.vue` | KPI bar at the top of every tab |
| `src/components/WeightsTab.vue` | Weights tuning tab |
| `src/components/AppHeader.vue` | Project header, search button, help modal |
| `src/components/CommandPalette.vue` | `⌘K` command palette |
| `src/components/DepGraph.vue` | D3 dependency graph |

### Data model invariant

`d.runs[]` is stored **newest-first** throughout the codebase (`runs[0]` = most recent run, `runs[length-1]` = oldest run). All display code that shows time-series data must reverse the array before rendering — use `[...d.runs].reverse()` in templates and chart data builders. Run numbers shown to users are chronological: Run #1 = oldest, Run #N = newest. Convert: `chronologicalRunNumber = d.runs.length - runsIndex`.
