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
mvn test-order:show
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
| `show` | Unified view: class order, method order, ML health (auto-detects available data) | Debug prioritization |
| `reactor-order` | Computes optimal module execution order for multi-module builds | Multi-module CI optimization |
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
| `detect-dependencies` | Detect order-dependent (flaky) tests via reordering | Flaky test detection (see [DETECT_DEPENDENCIES.md](DETECT_DEPENDENCIES.md)) |
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

Controls which production code changes are used to select and prioritize tests. Pass via `-Dtestorder.changeMode=<value>` or set in POM configuration.

| Mode | Best for | Summary |
|---|---|---|
| `auto` | Most projects | Selects the best available strategy for current environment |
| `since-last-run` | Local development without relying on git history | Compares current source snapshots with prior saved hashes |
| `since-last-commit` | CI/branch validation | Uses git diff from the previous commit context |
| `uncommitted` | Rapid local iteration | Uses working-tree/staged changes in current repository |
| `explicit` | Controlled CI pipelines | Caller passes exact class list via `testorder.changed.classes` |

### Mode Details and Trade-offs

#### `auto`
Automatically selects the best available strategy: `since-last-run` if a hash snapshot from a previous run exists, otherwise `since-last-commit`.

**Pros:** Works out-of-the-box with no configuration; adapts to whether a previous run snapshot is present.  
**Cons:** Non-deterministic across machines; the selected strategy can change if the snapshot is absent. If you need reproducible CI results, prefer an explicit mode.

---

#### `uncommitted`
Detects Java source files with working-tree or staged-but-not-committed changes (`git diff` + `git diff --cached`).

**Pros:** Very fast (single git subprocess); catches changes you haven't committed yet, which is ideal during active development. No hash files needed.  
**Cons:** Requires git. Does not capture changes introduced by earlier commits that are already committed but not yet tested. If a colleague's commit broke something, only the committer would see it.

**Use when:** Local dev loop where you want to test only what you're actively editing.

---

#### `since-last-commit`
Detects classes changed between the previous commit and HEAD (`git diff HEAD~1..HEAD`), then merges in any uncommitted changes on top.

**Pros:** Suitable for CI on pull requests — captures exactly what this PR changed. Works with most CI systems' shallow-clone setups (needs `fetch-depth: 2` at minimum).  
**Cons:** Requires git with at least one prior commit (`HEAD~1` must exist). The first commit on a new branch may report no changes. Cannot detect changes that span multiple commits without configuring a base ref.

**Use when:** PR/CI validation where you want to test what changed on the branch.

---

#### `since-last-run`
Compares current file hashes against a snapshot saved by the previous test run. Identifies any source files that changed between the two runs.

**Pros:** Works without git; portable across VCS-agnostic environments. Captures any file changes regardless of commit history. Automatically updates the snapshot after each run.  
**Cons:** Hash file must exist at `testorder.hashFile` path (defaults to `.test-order/hashes.lz4`). First run with no snapshot treats all files as changed. Hash files must be preserved between runs (may need to be committed or cached in CI).

**Use when:** Non-git environments, or when you want to test "what changed since last time I ran the test suite."

---

#### `explicit`
The caller provides the exact set of changed production class FQCNs via `testorder.changed.classes`.

**Pros:** Fully deterministic and reproducible — no dependency on git or file timestamps. Easy to integrate with external diff tools, artifact version comparisons, or custom CI scripts. No file I/O needed at detection time.  
**Cons:** Requires the caller to know and supply the changed class list. If the list is wrong or stale, test selection will be wrong. Useful only in scripted/automated pipelines.

**Use when:** Scripted CI pipelines where you already know the changed classes from a build system, artifact comparison, or external diff tool.

```bash
mvn test-order:auto test \
  -Dtestorder.changeMode=explicit \
  -Dtestorder.changed.classes=com.example.Service,com.example.Repository
```

---

### Fallback Behavior

If `uncommitted` or `since-last-commit` fails (e.g., git not available or no prior commit), test-order automatically falls back to `since-last-run` if a hash file is present. If no hash file exists, the failure is propagated.

### Recommended Defaults

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
| `testorder.tdd` | `false` | Enforce TDD discipline: new tests that pass without failing first are artificially failed |

### Files and Paths

| Property | Default |
|---|---|
| `testorder.index.path` (alias: `testorder.index`) | `${project.basedir}/.test-order/test-dependencies.lz4` |
| `testorder.state.path` (alias: `testorder.stateFile`) | `${project.basedir}/.test-order/state.lz4` |
| `testorder.depsDir` | `${project.build.directory}/test-order-deps` |
| `testorder.hashFile` | `${project.basedir}/.test-order/hashes.lz4` |
| `testorder.testHashFile` | `${project.basedir}/.test-order/test-hashes.lz4` |
| `testorder.methodHashFile` | `${project.basedir}/.test-order/method-hashes.lz4` |

### Selection and Change Detection

| Property | Default | Notes |
|---|---|---|
| `testorder.mode` | `auto` | `auto`, `learn`, `order`, `skip` — controls `auto` goal behaviour |
| `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `testorder.changed.classes` | unset | Required in `explicit` mode; comma-separated FQCNs |
| `testorder.changed.classes.file` | unset | Path to file of changed class FQCNs (one per line); merged with `testorder.changed.classes` |
| `testorder.changed.test.classes` | unset | Comma-separated changed test class FQCNs |
| `testorder.changed.methods` | unset | Comma-separated changed methods in `className#methodName` format |
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
| `testorder.auto.runRemaining` | `true` (Maven) / `false` (Gradle) | Print hint to run deferred tests (Maven); auto-run remaining tests via `finalizedBy` (Gradle) |

### Show (unified)

| Property | Default | Notes |
|---|---|---|
| `testorder.show.classes` | `true` | Include class-level order section |
| `testorder.show.methods` | `auto` | Include method-level order (`true`/`false`/`auto` = show if data exists) |
| `testorder.show.ml` | `auto` | Include ML health analysis (`true`/`false`/`auto` = show if history exists) |
| `testorder.show.all` | `false` | Force all sections on (equivalent to classes+methods+ml) |
| `testorder.showOrder.explain` | `false` | Show per-test scoring breakdown |
| `testorder.showOrder.fullNames` | `false` | Use fully qualified class names |
| `testorder.show.format` | `text` | Output format: `text` or `json` |
| `testorder.show.filter` | unset | Glob pattern to restrict output. Matches the full FQCN — use `*` to match any prefix (e.g. `*Service*,*Controller*`). Comma-separated patterns use OR semantics. Matching is case-insensitive. |

### Reactor Order

| Property | Default | Notes |
|---|---|---|
| `testorder.reactor.suggest` | `false` | Output only the `-pl` argument (machine-parseable for scripts) |
| `testorder.reactor.topN` | `5` | Number of top tests to display per module |

### Dashboard

The dashboard is an interactive HTML report with three tabs: **Tests** (ranked list, per-test score breakdown, run history), **Analytics** (APFD timeline, per-run drill-down, rank heatmap, failure correlation, and 15+ analysis panels), and **Weights** (interactive weight tuning with live rank preview). See [test-order-dashboard/README.md](../test-order-dashboard/README.md) for a full feature reference.

| Property | Default | Notes |
|---|---|---|
| `testorder.dashboard.output` | `${project.build.directory}/test-order-dashboard/index.html` | Output path for static dashboard |
| `testorder.dashboard.port` | `0` (auto) | Port for `serve` goal (`0` = ephemeral) |
| `testorder.serve.port` | — | Alias for `testorder.dashboard.port` (accepted for convenience) |
| `testorder.dashboard.open` | `false` | Open browser automatically after dashboard generation |
| `testorder.dashboard.regenerate` | `auto` | Force dashboard regeneration for `serve` goal (`auto`, `true`, `false`) |
| `testorder.dashboard.serveSeconds` | `0` | Stop `serve` automatically after N seconds (`0` = wait until interrupted) |

### Advanced

| Property | Default | Notes |
|---|---|---|
| `testorder.history.maxRuns` | `50` | Maximum run records to retain in state |
| `testorder.autoCompactEvery` | `50` | Rebuild index from `.deps` files every N order-mode runs (`0` = disabled) |
| `testorder.structuralDiff.enabled` | `true` | Use structural diff for change complexity scoring |
| `testorder.score.springContextGrouping` | `false` | Group tests sharing a Spring context |
| `testorder.score.ema.varianceThreshold` | `0.35` | EMA variance threshold for adaptive smoothing — stored in state file only; setting via `-D` has no effect |

### Selective Learn

Selective learn mode instruments only the classes reachable from the current source changes (changed classes + their transitive callees up to 4 hops via static call-graph analysis). This keeps per-run overhead proportional to the size of your change rather than the project size.

| Property | Default | Notes |
|---|---|---|
| `testorder.learn.selective` | `false` | Enable selective learn mode — only re-instruments changed classes and transitive callees |
| `testorder.auto.alwaysLearn` | `false` | Always run a learn pass in `auto` mode (combine with `selective` for cheap incremental updates) |

When no structural changes are detected the uncertain-class set is empty and instrumentation is skipped automatically — zero overhead on no-change runs.

The **Static Analysis** tab in the dashboard shows the instrumentation scope from the last selective-learn run (which classes were identified as uncertain, grouped by module). The tab appears automatically when selective-learn data is present.

### Instrumentation and Filtering

| Property | Default | Notes |
|---|---|---|
| `testorder.instrumentation.mode` | `MEMBER` | `CLASS`, `METHOD`, `MEMBER` |
| `testorder.includePackages` | unset | Restricts instrumentation scope |
| `testorder.filterByGroupId` | `true` | Falls back to project groupId when package detection is empty |
| `testorder.methodOrder.enabled` | `false` | Experimental method ordering |

### ML (Machine Learning) Predictions

| Property | Default | Notes |
|---|---|---|
| `testorder.ml.enabled` | `false` | Enable ML history collection during test runs |
| `testorder.ml.predictions.file` | auto | Intermediate predictions file consumed by test JVM |

When enabled, test-order records per-test outcomes (pass/fail, duration, exception type) after each run. With 5+ recorded runs, the ML layer can:

- **Predict failure probability** — Tribuo logistic regression trained on 26 features including change coupling, duration trends, co-failure patterns, and failure streaks.
- **Classify test health** — Statistical analysis (EWMA, autocorrelation, trend slope) labels tests as HEALTHY, DEGRADING, FLAKY, or FAILING.

ML data is shown in:
- `mvn test-order:show` (auto-detected when history exists)
- `mvn test-order:dashboard` (ML Health tab + P(fail) column)

### Scoring Overrides

| Property | Default | Description |
|---|---|---|
| `testorder.score.newTest` | `15` | Bonus for test classes not in the dependency index |
| `testorder.score.changedTest` | `9` | Bonus for changed test sources |
| `testorder.score.maxFailure` | `5` | Cap on failure-based bonus |
| `testorder.score.speed` | `1` | Bonus for fast tests (full at 1/8× median) |
| `testorder.score.speedPenalty` | `1` | Penalty for slow tests (full at 8× median) |
| `testorder.score.depOverlap` | `5` | Max score from dependency overlap (sqrt-normalized) |
| `testorder.score.changeComplexity` | `2` | Complexity-weighted overlap using compressed diff size |
| `testorder.score.staticFieldBonus` | `0` | Fixed bonus for tests overlapping a changed static field (requires `MEMBER` mode) |
| `testorder.score.coverageBonus` | `0` | Greedy set-cover bonus; when >0 replaces `depOverlap`+`changeComplexity` |
| `testorder.weights.file` | unset | Path to TOML weights file; overrides all `testorder.score.*` properties when set |

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

### ML-powered prioritization

```bash
# Enable ML history collection (add to POM or pass on every run)
mvn test -Dtestorder.ml.enabled=true

# After 5+ runs, view ML health analysis
mvn test-order:show -Dtestorder.show.ml=true

# Full report (class order + method order + ML) in JSON
mvn test-order:show -Dtestorder.show.all=true -Dtestorder.show.format=json

# Dashboard includes ML health tab automatically
mvn test-order:dashboard
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

## TDD Enforcement

When `testorder.tdd=true`, new test classes and methods that pass without a prior failure
in the state file are artificially failed with a descriptive error message.
This enforces the red-green-refactor cycle: write the test, see it **fail**, then make it pass.

```bash
# Maven
mvn test -Dtestorder.tdd=true

# Gradle
./gradlew test -Dtestorder.tdd=true
```

Or set it permanently in the plugin configuration (Maven `<tdd>true</tdd>`, Gradle `tdd = true`).

Behaviour:
- **First run** (no state file): enforcement is skipped — all tests pass normally.
- **Known test passes**: no enforcement — only new classes/methods are checked.
- **New test class passes without prior failure**: artificially failed.
- **New test method passes without prior failure**: artificially failed (only when method-level data exists in state).
- **Test that already fails**: not flagged — TDD discipline is satisfied.

## Standalone CLI Tool

The `test-order-core` module ships a standalone CLI jar:

```bash
java -jar test-order-core-jar-with-dependencies.jar <command> [options]
```

All commands support `--help` for detailed option descriptions.

**Exit codes:** All CLI commands return `0` on success and `1` on failure. Failures include: missing index file, invalid arguments (out-of-range threshold, bad mode string), index is empty when content is required (`dump`, `aggregate`), missing source root directory (`hash-snapshot`), and I/O errors. The `changed` command returns `0` even when no changes are detected — check stdout for `"No changes detected."` instead.

| Command | Args | Description |
|---|---|---|
| `aggregate` | `<depsDir> -o <output>` | Merge `.deps` files into a dependency index |
| `affected` | `<indexFile> -c <classes>` | List test classes affected by a set of changed class FQCNs |
| `stats` | `<indexFile>` | Print dependency index statistics (class count, unique deps, avg deps) |
| `dump` | `<indexFile> [-o file]` | Dump index as human-readable text (stdout or file) |
| `export-json` | `<indexFile> [-o file]` | Export dependency index (and optionally state history) as JSON |
| `select` | `<indexFile>` | Select a prioritized subset: new tests + top-N scored + M diverse fast tests |
| `optimize` | `[stateFile]` | Analyze run history and optimize scoring weights |
| `hash-snapshot` | `[-s sourceRoot] [-o hashFile]` | Scan source tree and save file hash snapshot (for `since-last-run`) |
| `changed` | `[--mode M] [--classes C]` | Detect changed production classes using the specified mode |
| `run` | `<indexFile> [--mode M]` | Detect changes and print the affected test classes |
| `struct-diff` | `[files…] [--ref ref]` | Structural diff of Java files (types, methods, fields) against git |
| `advise` | `<indexFile> [--threshold T]` | Identify test classes with low method cohesion — candidates for splitting |

### `advise` — Test Class Split Analysis

Requires per-method dependency data (collected when `testorder.instrumentation.mode=METHOD` or `MEMBER`). Analyzes the pairwise Jaccard similarity of each test method's dependency set within a class. Classes whose methods cover largely disjoint production code are split candidates — breaking them up lets test-order schedule them independently and improves prioritization precision.

```bash
java -jar test-order-core.jar advise .test-order/test-dependencies.lz4
java -jar test-order-core.jar advise .test-order/test-dependencies.lz4 --threshold 0.4 --verbose
```

| Option | Default | Description |
|---|---|---|
| `--threshold` | `0.3` | Similarity threshold in [0,1]; classes below this value are flagged |
| `--verbose` / `-v` | false | Print per-class details including suggested split groups |

### `struct-diff` — Structural Change Analysis

Shows which Java types, methods, and fields have been added, changed, or removed in your source tree — without running a full build.

```bash
# Show uncommitted structural changes
java -jar test-order-core.jar struct-diff

# Show changes in the last commit
java -jar test-order-core.jar struct-diff --since-last-commit

# Diff specific files against a git ref
java -jar test-order-core.jar struct-diff src/main/java/com/example/Service.java --ref HEAD~3
```

## Validation Rules (High Impact)

- `testorder.changeMode` must be one of the supported modes.
- `testorder.changed.classes` is required when `changeMode=explicit`. If omitted, a warning is printed and the empty set is returned (no tests selected beyond new and `@AlwaysRun` tests).
- `testorder.select.topN` must be `>= -1` (`-1` = all affected, positive = exact count, `0` = no top-scored tests but new and `@AlwaysRun` tests still run — a warning is emitted).
- `testorder.select.randomM` must be `>= 0`.
- `testorder.instrumentation.mode` must be one of: `CLASS`, `METHOD`, `MEMBER`.
- `testorder.coverage.threshold` must be `>= 1` (minimum number of exercising tests for a class to be "well-tested").

## Notes on Property Names

Some runtime keys have canonical and legacy aliases. The following are commonly interchangeable:

- `testorder.index.path` and `testorder.index`
- `testorder.state.path` and `testorder.stateFile`
- `testorder.source.root` and `testorder.sourceRoot`
- `testorder.methodOrder.enabled` and `testorder.methodOrderingEnabled`
