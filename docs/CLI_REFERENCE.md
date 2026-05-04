# test-order CLI Reference

Practical reference for Maven plugin goals and the most important configuration properties.

## Quick Start

```bash
# Typical local workflow
mvn test-order:combined test

# Run deferred tests from the previous combined/select run
mvn test-order:run-remaining test

# Inspect ordering decisions without executing tests
mvn test-order:show-order
```

## Maven Goals

| Goal | Purpose | Typical use |
|---|---|---|
| `prepare` | Validates setup and writes plugin/runtime configuration | First-time setup, troubleshooting |
| `snapshot` | Learns dependencies and updates index data | Rebuild dependency knowledge |
| `aggregate` | Aggregates `.deps` files into the dependency index | After distributed/parallel learn runs |
| `combined` | Main developer workflow: select high-value subset and run it | Fast feedback loop |
| `select` | Writes selected tests to file without executing tests | CI orchestration, custom runners |
| `run-remaining` | Executes deferred tests from prior selection | Follow-up confidence run |
| `show-order` | Prints ranking/order and score breakdown | Debug prioritization |
| `dump` | Prints dependency index contents | Verify learned dependency mapping |
| `export-json` | Exports dependency index as JSON | Share/index inspection tooling |
| `optimize` | Re-optimizes scoring weights from run history | Periodic tuning |
| `dashboard` | Generates HTML dashboard | Visual analysis |
| `serve` | Serves dashboard via local HTTP server | Browser compatibility / sharing |
| `coverage` | Generates least-tested / coverage reports | Coverage gap analysis |

## Operation Modes (`testorder.mode`)

Controls what the `combined` goal does. Pass via `-Dtestorder.mode=<value>` or set in POM configuration.

| Value | Behaviour |
|---|---|
| `auto` (default) | Learn if no index exists; otherwise select/order. |
| `learn` | Always run learn regardless of whether an index already exists. |
| `order` | Require an existing index and run select/order. Warns and exits if no index is found — **does not** fall back to learn. |
| `skip` | Do nothing. Surefire runs tests in its default order without any test-order influence. |
| `combined` | Alias for `auto`. |

Examples:

```bash
# Force a fresh learn pass (re-baseline the index)
mvn test-order:combined test -Dtestorder.mode=learn

# Require index; fail gracefully instead of silently rebaselining
mvn test-order:combined test -Dtestorder.mode=order

# Disable test-order without removing the plugin (e.g., for a hotfix)
mvn test-order:combined test -Dtestorder.mode=skip
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
| `testorder.mode` | `auto` | `auto`, `learn`, `order`, `skip` — controls `combined` goal behaviour |
| `testorder.changeMode` | `uncommitted` | `auto`, `since-last-run`, `since-last-commit`, `uncommitted`, `explicit` |
| `testorder.changed.classes` | unset | Required in `explicit` mode |
| `testorder.select.topN` | `20` | Always included top-ranked tests |
| `testorder.select.randomM` | `10` | Diversity sampling |
| `testorder.select.seed` | unset | Reproducible random selection |
| `testorder.select.selectedFile` | `${project.build.directory}/test-order-selected.txt` | Selected list output |
| `testorder.select.remainingFile` | `${project.build.directory}/test-order-remaining.txt` | Deferred list output |
| `testorder.exportJson.output` | unset | Output path for `export-json` goal (stdout when unset) |

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
mvn test-order:combined test \
  -Dtestorder.changeMode=uncommitted \
  -Dtestorder.select.topN=5 \
  -Dtestorder.select.randomM=0
```

### PR/CI subset

```bash
mvn test-order:combined test \
  -Dtestorder.changeMode=since-last-commit \
  -Dtestorder.select.topN=30 \
  -Dtestorder.select.randomM=10
```

### Explicit CI contract

```bash
mvn test-order:combined test \
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
          mvn test-order:combined test \
            -Dtestorder.changeMode=since-last-commit \
            -Dtestorder.select.topN=20 \
            -Dtestorder.select.randomM=10

      - name: Run deferred tests
        if: success()
        run: mvn test-order:run-remaining test
```

### Multi-module usage

```bash
# Entire reactor
mvn test-order:combined test

# Specific modules
mvn -pl core,api test-order:combined test
```

## Validation Rules (High Impact)

- `testorder.changeMode` must be one of the supported modes.
- `testorder.changed.classes` is required when `changeMode=explicit`.
- `testorder.select.topN` and `testorder.select.randomM` must be `>= 0`.
- `testorder.instrumentation.mode` must be one of: `METHOD_ENTRY`, `FULL`, `FULL_METHOD`, `FULL_MEMBER`.
- `coverage.threshold` must be between `0` and `100`.

## Notes on Property Names

Some runtime keys have canonical and legacy aliases. The following are commonly interchangeable:

- `testorder.index.path` and `testorder.index`
- `testorder.state.path` and `testorder.stateFile`
- `testorder.source.root` and `testorder.sourceRoot`
- `testorder.methodOrder.enabled` and `testorder.methodOrderingEnabled`

