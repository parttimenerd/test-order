# Dashboard

The test-order dashboard is a self-contained HTML file that visualises your test
suite's prioritisation state. Generate it with:

```sh
mvn test-order:dashboard
```

This creates `target/test-order-dashboard/index.html`. Open it in any browser —
it works from `file://` with no server required.

For a live-reloading server (useful during development):

```sh
mvn test-order:serve-dashboard
```

---

## Prerequisites

The dashboard reads two data files produced by the plugin:

| File | Produced by | Required? |
|------|-------------|-----------|
| `.test-order/test-dependencies.lz4` | `mvn test -Dtest-order.mode=learn` | Yes — test→source dependency index |
| `.test-order/state.lz4` | Any `learn` or `order` run | Yes — durations, failure scores, run history |

If either file is missing the dashboard shows a "No test data yet" splash
with instructions.

---

## Layout

The dashboard has four main regions:

```
┌──────────────────────────────────────────┐
│ Header    project name · stats · changed │
├──────────────────────────────────────────┤
│ KPI Row   APFD · Failures · Speed · Δ   │
├────────┬─────────────────────────────────┤
│        │ Tab content area                │
│ Side-  │  [Tests]  [Analytics]  [Weights]│
│ bar    │                                 │
│        │                                 │
├────────┴─────────────────────────────────┤
│ Footer   version · path · timestamp      │
└──────────────────────────────────────────┘
```

### Header

Shows the project name, test count, median duration, and the number of
changed source classes. Click **"N changed ▾"** to expand the changed-classes
panel listing every affected source class and test class.

### KPI Row

Four summary cards visible on all tabs:

| Card | Description |
|------|-------------|
| **Avg APFD** | Average Percentage of Faults Detected across all runs. 1.0 = perfect, 0.5 = random order. Green above 0.7, yellow above 0.5, red below. |
| **Latest Failures** | Number of failing tests in the most recent run. |
| **Fastest / Slowest** | The fastest and slowest tests by EMA duration. |
| **Changed Tests** | How many test classes overlap with changed source code. |

### Sidebar

A scrollable list of all test classes, sorted by rank (default). Each row
shows rank number, short class name, priority score, category badges, and
duration. Features:

- **Filter** — type in the search box to narrow the list.
- **Sort** — click Rank / Name / Score / Dur buttons to re-sort.
- **Select** — click a test to see its detail panel.
- **Multi-select** — Ctrl/⌘+click for multiple tests; Shift+click for range.
- **Method drill-down** — double-click a test to expand its method sub-list
  (requires `FULL_METHOD` or `FULL_MEMBER` instrumentation mode).

### Footer

Shows the plugin version, state file path, generation timestamp, and total
number of recorded runs.

---

## Tabs

### Tests (default)

Three view modes depending on selection:

#### Overview (no selection)

Five KPI chips (Total / Changed / New / Failing / Static overlap) plus a
sortable table of all tests by priority. Click column headers to re-sort.
Click any row to select it.

#### Single-test detail

When one test is selected, the main area shows:

- **Score breakdown** — horizontal bar chart splitting the total score into
  components: set-cover bonus, dependency overlap, complexity, changed,
  new, speed±, static overlap, and failure score.
- **Run history** — coloured squares (green = pass, red = fail, empty = absent)
  for each recorded run.
- **Duration history** — EMA duration trend line.
- **Score over time / Position over time** — line charts showing how this test's
  score and rank evolved across runs.
- **Method cards** — if method-level data is available, a grid of methods with
  their dependency counts. Click a method card to highlight it in the
  dependency graph.

#### Multi-select comparison

When multiple tests are selected (Ctrl/⌘+click), a comparison table shows
all selected tests side-by-side with their scores, ranks, and deltas.

### Analytics

Charts and distributions across the full test suite:

- **Timeline** — APFD over time (with 0.5 random baseline), failures per run,
  first-failure position, and test count per run.
- **Distributions** — histograms for score, duration buckets, dependency
  counts, and top-20 tests by failure score.
- **Coverage treemap** — an interactive D3 treemap of source classes sized by
  test count and coloured from red (few tests) to green (many tests).
  Hover for details; click to open a class detail panel showing which tests
  cover it and (if available) member-level breakdown.
- **Selection coverage** — when tests are selected in the sidebar, a progress
  bar shows what percentage of source classes the selection covers.

### Weights

A what-if explorer for the scoring formula:

- **9 weight sliders** — adjust `newTest`, `changedTest`, `maxFailure`, `speed`,
  `speedPenalty`, `depOverlap`, `changeComplexity`, `staticFieldBonus`, and
  `coverageBonus` in real time.
- **Reset** — restores all sliders to the project's current weight values.
- **Simulation table** — shows every test's original vs. simulated rank and
  score under the new weights. Rows with |Δ rank| > 5 are highlighted.
  Click column headers to sort.

---

## Badges

Tests can have one or more of these badges (shown as abbreviations in the
sidebar, full text in the detail view):

| Badge | Meaning |
|-------|---------|
| **C** / CHANGED | Test exercises a source class that has changed |
| **N** / NEW | Test class was not present in the previous learn run |
| **F** / FAILING | Test has a non-zero failure score (failed recently) |
| **⚡** / FAST | Duration is significantly below the median |
| **🐢** / SLOW | Duration is significantly above the median |
| **S** / STATIC | Test accesses static fields that overlap with changed classes |

---

## Dependency Graph

When a test is selected, a D3 force-directed graph appears showing the
selected test as a circle connected to its source-class dependencies
(rectangles). Nodes for changed classes are highlighted.

**Graph modes** (buttons above the graph):

| Mode | Shows |
|------|-------|
| Focus | Only the selected test(s) and their direct dependencies |
| Changed subgraph | All tests and source classes involved in the current change set |
| Full | The entire dependency graph (can be slow with many tests) |

---

## Scoring Formula

Each test's priority score is the sum of weighted components:

$$\text{score} = \sum_{i} w_i \cdot c_i$$

Where the components $c_i$ and their weights $w_i$ are:

| Component | Weight param | Description |
|-----------|-------------|-------------|
| Set-cover bonus | `coverageBonus` | Greedy set-cover algorithm bonus (×0.8 decay per step) |
| Dep overlap | `depOverlap` | Count of changed source classes exercised by this test |
| Complexity | `changeComplexity` | Structural complexity of changed code paths |
| Changed | `changedTest` | 1 if the test class itself changed, 0 otherwise |
| New | `newTest` | 1 if the test class is new, 0 otherwise |
| Speed+ | `speed` | Bonus for tests faster than median (negative speed ratio) |
| Speed− | `speedPenalty` | Penalty for tests slower than median (positive speed ratio) |
| Static overlap | `staticFieldBonus` | 1 if test touches static fields in changed classes |
| Failures | `maxFailure` | Decay-weighted failure score from recent runs |

---

## Architecture

The dashboard is built as a Vue 3 + TypeScript + Vite single-page application
bundled into one IIFE script. At build time:

1. `frontend-maven-plugin` runs `npm ci` and `npx vite build` in
   `test-order-dashboard/src/main/dashboard/`.
2. Vite produces `dist/dashboard.js` (IIFE, ~440 KB) and `dist/dashboard.css`
   (~12 KB) which bundle Vue, Chart.js 4, and D3 7.
3. `DashboardResources.java` assembles `template.html` by inlining the CSS
   and JS into `<style>` / `<script>` placeholders.
4. `DashboardGenerator.java` converts scored test data, run history, weights,
   and coverage into a JSON object.
5. The JSON is injected into a `<script id="dashboard-data" type="application/json">`
   tag in the HTML template.
6. On page load, the Vue app reads the JSON from the DOM and renders the
   dashboard client-side.

### Component Structure

```
App.vue
├── NoDataSplash.vue     — empty state when no data files exist
├── AppHeader.vue        — project name, stats, changed-classes panel
├── KpiRow.vue           — 4 summary KPI cards
├── TabBar.vue           — Tests / Analytics / Weights tab navigation
├── TestSidebar.vue      — scrollable test list with search, sort, selection
│   └── TestBadges.vue   — inline category badges per test
├── TestsTab.vue         — overview table, detail panel, charts
│   ├── TestBadges.vue
│   └── (Chart.js canvases for score breakdown, history, trends)
├── AnalyticsTab.vue     — timeline charts, distributions, coverage treemap
│   └── (D3 treemap SVG)
├── WeightsTab.vue       — weight sliders, simulation table
├── DepGraph.vue         — D3 force-directed dependency graph
└── AppFooter.vue        — version, file path, timestamp, run count
```

### Key Files

| File | Purpose |
|------|---------|
| `src/main.ts` | Vue app entry point, mounts `App.vue` |
| `src/data.ts` | Extracts and parses the JSON data from the DOM |
| `src/types.ts` | TypeScript interfaces (`DashboardData`, `TestEntry`, `RunRecord`, etc.) |
| `src/composables/useDashboard.ts` | Reactive state, computed properties, user actions |
| `src/composables/useCharts.ts` | Chart.js wrapper with dark theme defaults |
| `src/utils.ts` | Formatters (`sn`, `fmtDur`, `fmtTime`), scoring helpers |

---

## Testing

The dashboard has end-to-end tests using Playwright (headless Chromium)
in the `test-order-dashboard-ui-tests` module.

### Running Tests

```sh
# First-time browser install
mvn exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium" \
  -pl test-order-dashboard-ui-tests

# Run tests
mvn verify -pl test-order-dashboard-ui-tests
```

### Test Coverage

The Playwright tests cover:

- **App initialisation** — Vue mounts, data populates, no empty-state splash
- **Test list** — sidebar shows all tests with ranks, scores, badges
- **Tab navigation** — switching between Tests, Analytics, Weights tabs
- **Chart rendering** — Chart.js canvases render in detail view and analytics
- **D3 dependency graph** — SVG with circle nodes renders for selected tests
- **Weight explorer** — sliders present, simulation table populated, reset works
- **Test selection** — single click opens detail panel with score breakdown
- **Multi-select** — Ctrl/⌘+click selects multiple tests, shows comparison
- **Search/filter** — narrows test list, case-insensitive, empty results handled
- **Sidebar sorting** — sort buttons reorder test list, active state highlighted
- **Test badges** — changed (C), failing (F), slow (🐢) badges appear correctly
- **Changed classes panel** — opens/closes on click, shows class names
- **Coverage** — treemap SVG renders, KPI values display, progress bar visible
- **Asset serving** — HTTP 200, self-contained HTML, path traversal protection
- **No JS errors** — console error collection across all test interactions
- **Footer** — version and timestamp present
- **KPI values** — APFD reasonable, failure count shown
- **Overview table** — headers sortable, test data visible

### Test Fixture

`DashboardServerFixture.java` creates a synthetic dataset with:

- 10 test classes with realistic cross-dependencies
- 2 run records (all-pass, then 2 failures)
- `UserService` marked as changed source class
- Varied durations (75ms–800ms) producing fast/slow badges
- Coverage data automatically derived from the dependency map

The fixture starts a local HTTP server on a random port and serves the
fully assembled dashboard HTML to Playwright.
