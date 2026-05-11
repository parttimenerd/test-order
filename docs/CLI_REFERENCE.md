# test-order CLI Reference

Practical reference for Maven plugin goals and the most important configuration properties.

## Quick Start

```bash
# Typical local workflow
mvn test-order:auto test

# If the current project does not declare the plugin and Maven cannot resolve
# the test-order prefix, use the fully-qualified goal instead.
mvn me.bechberger:test-order-maven-plugin:<version>:auto test

# Run deferred tests from the previous auto/select run
mvn test-order:run-remaining test

# Inspect ordering decisions without executing tests
mvn test-order:show-order
```

## Maven Goals

| Goal | Purpose | Typical use |
|---|---|---|
| `prepare` | Validates setup and writes plugin/runtime configuration | First-time setup, troubleshooting |
| `learn` | Attach agent for learn mode (pair with `test` phase) | Rebuild dependency index |
| `auto` | Main developer workflow: select high-value subset and run it | Fast feedback loop |
| `select` | Writes selected tests to file without executing tests | CI orchestration, custom runners |
| `run-remaining` | Executes deferred tests from prior selection | Follow-up confidence run |
| `tiered-select` | Splits tests into tier 1/2/3 files and runs tier 1 | Three-phase CI fail-fast workflow |
| `run-tier` | Executes tier 2 or tier 3 from prior tiered selection | Progressive confidence after tier 1 |
| `show-order` | Prints ranking/order and score breakdown | Debug prioritization |
| `show-method-order` | Prints method-level priority order within each test class | Debug method ordering |
| `dashboard` | Generates HTML dashboard | Visual analysis |
| `serve` | Serves dashboard via local HTTP server | Browser compatibility / sharing |
| `optimize` | Re-optimizes scoring weights from run history | Periodic tuning |
| `snapshot` | Save source/test file hash snapshots | Since-last-run change detection |
| `aggregate` | Aggregates `.deps` files into the dependency index | After distributed/parallel learn runs |
| `dump` | Prints dependency index contents | Verify learned dependency mapping |
| `export-json` | Exports dependency index as JSON | Share/index inspection tooling |
| `diagnose` | Runs diagnostic health checks on plugin setup and state | Troubleshooting |
| `compact` | Rebuilds the dependency index from `.deps` files (removes stale entries) | Fix corrupted index / clean up |
| `clean` | Removes all test-order state, indexes, and hash files | Start fresh |
| `download` | Downloads dependency index from CI artifact store | CI warm-start |
| `coverage` | Generates least-tested / coverage reports | Coverage gap analysis |
| `detect-dependencies` | Detect order-dependent (flaky) tests via reordering | Flaky test detection |
| `metrics` | Exports test-order metrics as JSON | CI/CD reporting, dashboards |
| `help` | Displays all goals and common properties | Quick reference |

## Operation Modes (`testorder.mode`)

Controls what the `auto` goal does. Pass via `-Dtestorder.mode=<value>` or set in POM configuration.

| Value | Behaviour |
|---|---|
| `auto` (default) | Learn if no index exists; otherwise select/order. |
| `learn` | Always run learn regardless of whether an index already exists. |
| `order` | Require an existing index and run select/order. Warns and exits if no index is found — **does not** fall back to learn. |
| `skip` | Do nothing. Surefire runs tests in its default order without any test-order influence. |


Examples:

```bash
# Force a fresh learn pass (re-baseline the index)
mvn test-order:auto test -Dtestorder.mode=learn

# Require index; fail gracefully instead of silently rebaselining
mvn test-order:auto test -Dtestorder.mode=order

# Disable test-order without removing the plugin (e.g., for a hotfix)
mvn test-order:auto test -Dtestorder.mode=skip
```

## Change Detection Modes

| Mode | Best for | Summary |
|---|---|---|
| `auto` | Most projects | Selects the best available strategy for current environment |
| `since-last-run` | Local development without relying on git history | Compares current source snapshots with prior saved hashes |
| `since-last-commit` | CI/branch validation | Uses git diff from the previous commit context |
| `uncommitted` | Rapid local iteration | Uses working-tree/staged changes in current repository |
| `explicit` | Controlled CI pipelines | Caller passes exact class list via `testorder.changed.classes` |

Recommended defaults:

- Local developer loop: `uncommitted`
- Pull request / CI checks: `since-last-commit`
- Scripted deterministic pipelines: `explicit`
- Mixed environments or simple setup: `auto`

## Core Properties

### General

| Property | Default | Notes |
|---|---|---|
| `testorder.skip` | `false` | Skip the plugin entirely for a vanilla test run |
| `testorder.debug` | `false` | Enable debug-level logging |

### Files and Paths

| Property | Default |
|---|---|
| `testorder.index` | `${project.basedir}/.test-order/test-dependencies.lz4` |
| `testorder.stateFile` | `${project.basedir}/.test-order/state.lz4` |
| `testorder.depsDir` | `${project.build.directory}/test-order-deps` |
| `testorder.hashFile` | `${project.basedir}/.test-order/hashes.lz4` |
| `testorder.testHashFile` | `${project.basedir}/.test-order/test-hashes.lz4` |
| `testorder.methodHashFile` | `${project.basedir}/.test-order/method-hashes.lz4` |

### Selection and Change Detection

| Property | Default | Notes |
|---|---|---|
| `testorder.mode` | `auto` | `auto`, `learn`, `order`, `skip` — controls `auto` goal behaviour |
| `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `testorder.changed.classes` | unset | Required in `explicit` mode |
| `testorder.select.topN` | `-1` | Top-ranked tests to include (`-1` = all affected) |
| `testorder.select.randomM` | `10` | Diversity sampling |
| `testorder.select.seed` | unset | Reproducible random selection |
| `testorder.select.selectedFile` | `${project.build.directory}/test-order-selected.txt` | Selected list output |
| `testorder.select.remainingFile` | `${project.build.directory}/test-order-remaining.txt` | Deferred list output |
| `testorder.exportJson.output` | unset | Output path for `export-json` goal (stdout when unset) |

### Tiered CI

| Property | Default | Notes |
|---|---|---|
| `testorder.tiered.tier2Fraction` | `0.5` | Tier-2 fraction of remaining suite (duration budget if `weightByDuration=true`) |
| `testorder.tiered.weightByDuration` | `true` | Select tier 2 by expected duration budget instead of test count |
| `testorder.tiered.tier1File` | `${project.build.directory}/test-order-tier1.txt` | Tier-1 list output |
| `testorder.tiered.tier2File` | `${project.build.directory}/test-order-tier2.txt` | Tier-2 list output |
| `testorder.tiered.tier3File` | `${project.build.directory}/test-order-tier3.txt` | Tier-3 list output |
| `testorder.tiered.currentTier` | unset | Required for `run-tier` (`2` or `3`) |

### Auto Mode

| Property | Default | Notes |
|---|---|---|
| `testorder.autoLearnRunThreshold` | `10` | Force re-learn after N order-mode runs (`0` = disabled) |
| `testorder.autoLearnDiffThreshold` | `0` | Re-learn when changed-class count reaches this (`0` = disabled) |
| `testorder.auto.optimizeEvery` | `10` | Run weight optimization every N auto runs (`0` = disabled) |
| `testorder.auto.runRemaining` | `true` | Print hint to run deferred tests (Maven); auto-run remaining tests via `finalizedBy` (Gradle) |

### Show-Order

| Property | Default | Notes |
|---|---|---|
| `testorder.showOrder.explain` | `false` | Show per-test scoring breakdown |
| `testorder.showOrder.fullNames` | `false` | Use fully qualified class names |

### Dashboard

| Property | Default | Notes |
|---|---|---|
| `testorder.dashboard.output` | `${project.build.directory}/test-order-dashboard/index.html` | Output path for static dashboard |
| `testorder.dashboard.port` | `0` (auto) | Port for `serve` goal (`0` = ephemeral) |
| `testorder.dashboard.open` | `false` | Open browser automatically after dashboard generation |
| `testorder.dashboard.regenerate` | `auto` | Force dashboard regeneration for `serve` goal |
| `testorder.dashboard.serveSeconds` | `0` | Stop `serve` automatically after N seconds (`0` = wait until interrupted) |

### Advanced

| Property | Default | Notes |
|---|---|---|
| `testorder.history.maxRuns` | `50` | Maximum run records to retain in state |
| `testorder.autoCompactEvery` | `50` | Rebuild index from `.deps` files every N order-mode runs (`0` = disabled) |
| `testorder.structuralDiff.enabled` | `true` | Use structural diff for change complexity scoring |
| `testorder.score.springContextGrouping` | `false` | Group tests sharing a Spring context |
| `testorder.score.ema.varianceThreshold` | `0.35` | EMA variance threshold for adaptive smoothing (`0` = no adaptation) |

### Instrumentation and Filtering

| Property | Default | Notes |
|---|---|---|
| `testorder.instrumentation.mode` | `FULL` | `METHOD_ENTRY`, `FULL`, `FULL_METHOD`, `FULL_MEMBER` |
| `testorder.includePackages` | unset | Restricts instrumentation scope |
| `testorder.filterByGroupId` | `true` | Falls back to project groupId when package detection is empty |
| `testorder.methodOrder.enabled` | `false` | Experimental method ordering |

### Scoring Overrides

| Property | Default |
|---|---|
| `testorder.score.newTest` | `15` |
| `testorder.score.changedTest` | `9` |
| `testorder.score.maxFailure` | `5` |
| `testorder.score.speed` | `1` |
| `testorder.score.speedPenalty` | `1` |
| `testorder.score.depOverlap` | `5` |
| `testorder.score.changeComplexity` | `2` |
| `testorder.score.staticFieldBonus` | `0` |
| `testorder.score.coverageBonus` | `0` |
| `testorder.weights.file` | unset |

## Common Recipes

### Fast local loop

```bash
mvn test-order:auto test \
  -Dtestorder.changeMode=uncommitted \
  -Dtestorder.select.topN=5 \
  -Dtestorder.select.randomM=0
```

### PR/CI subset

```bash
mvn test-order:auto test \
  -Dtestorder.changeMode=since-last-commit \
  -Dtestorder.select.topN=30 \
  -Dtestorder.select.randomM=10
```

### Explicit CI contract

```bash
mvn test-order:auto test \
  -Dtestorder.changeMode=explicit \
  -Dtestorder.changed.classes=com.example.Service,com.example.Repository
```

### Dashboard

```bash
mvn test-order:dashboard
mvn test-order:serve -Dtestorder.dashboard.port=8080
```

Useful serve options:

- `-Dtestorder.dashboard.output=...` to change output path
- `-Dtestorder.dashboard.port=8080` to set serving port
- `-Dtestorder.dashboard.regenerate=true` to force regeneration before serving
- `-Dtestorder.dashboard.serveSeconds=30` to stop automatically after 30 seconds

`serve` hosts the configured output file over local HTTP, which is useful for:

- quick local sharing
- stable refresh URLs
- browser auto-open workflows

## Advanced Workflow Patterns

### GitHub Actions example

```yaml
name: Selective Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Fast selective run
        run: |
          mvn test-order:auto test \
            -Dtestorder.changeMode=since-last-commit \
            -Dtestorder.select.topN=20 \
            -Dtestorder.select.randomM=10

      - name: Run deferred tests
        if: success()
        run: mvn test-order:run-remaining test

      - name: Alternative tiered workflow (tier 1)
        if: false
        run: mvn test-order:tiered-select test

      - name: Alternative tiered workflow (tier 2)
        if: false
        run: mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2

      - name: Alternative tiered workflow (tier 3)
        if: false
        run: mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
```

### Multi-module usage

```bash
# Entire reactor
mvn test-order:auto test

# Specific modules
mvn -pl core,api test-order:auto test
```

## Validation Rules (High Impact)

- `testorder.changeMode` must be one of the supported modes.
- `testorder.changed.classes` is required when `changeMode=explicit`.
- `testorder.select.topN` must be `>= -1` (`-1` = all affected, `0` = none, `>0` = exact count).
- `testorder.select.randomM` must be `>= 0`.
- `testorder.instrumentation.mode` must be one of: `METHOD_ENTRY`, `FULL`, `FULL_METHOD`, `FULL_MEMBER`.
- `testorder.coverage.threshold` must be `>= 1` (minimum number of exercising tests for a class to be "well-tested").

## Notes on Property Names

Some runtime keys have canonical and legacy aliases. The following are commonly interchangeable:

- `testorder.index.path` and `testorder.index`
- `testorder.state.path` and `testorder.stateFile`
- `testorder.source.root` and `testorder.sourceRoot`
- `testorder.methodOrder.enabled` and `testorder.methodOrderingEnabled`
