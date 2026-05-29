# Scoring System

Each test class receives a score. Tests are sorted by descending score, with faster tests first among ties.

## Score Components

<!-- BEGIN WEIGHTS TABLE -->
| Component | Default | Config property | Description |
|---|---|---|---|
| **New test bonus** | 15 | `testorder.score.newTest` | Bonus for new test classes not in the dependency index |
| **Changed test bonus** | 9 | `testorder.score.changedTest` | Bonus for changed test sources |
| **Failure bonus** | 1–5 | `testorder.score.maxFailure` | Cap on failure-based bonus |
| **Speed bonus** | 1 | `testorder.score.speed` | Bonus for fast tests (logarithmic scale: full bonus at 1/8× median, zero at median) |
| **Speed penalty** | 1 | `testorder.score.speedPenalty` | Penalty for slow tests (logarithmic scale: full penalty at 8× median, zero at median) |
| **Dependency overlap** | 5 (max) | `testorder.score.depOverlap` | Max score from dependency overlap (sqrt-normalized: overlap/√totalDeps × weight). Disabled when `coverageBonus > 0`. |
| **Change complexity** | 2 (max) | `testorder.score.changeComplexity` | Complexity-weighted overlap using Deflate-compressed file size as information-density proxy. Disabled when `coverageBonus > 0`. |
| **Static field bonus** | 0 | `testorder.score.staticFieldBonus` | Fixed bonus when a test directly overlaps a changed static field. Only applied with member-level (`MEMBER` mode) overlap data. |
| **Coverage bonus** | 0 | `testorder.score.coverageBonus` | Greedy set-cover bonus: replaces `depOverlap` + `changeComplexity` with geometrically declining bonuses (×0.8) for tests that collectively cover all changed classes. Set to 0 (default) to use per-test scoring instead. |
| **Kill-rate bonus** | 0 | `testorder.score.killRateBonus` | Bonus scaled by mutation kill rate (requires `analyze-mutations` data). Tests with a high kill rate also get a multiplier on dep-overlap: `depOverlapScore × (0.5 + killRate × 0.5)`. Default 0 — no effect until explicitly set. |
<!-- END WEIGHTS TABLE -->

## Formula

<!-- BEGIN WEIGHTS FORMULA -->
```
score = (isNew ? newTestBonus : 0)
      + (isChanged ? changedTestBonus : 0)
      + min(ceil(recencyWeightedFailures), maxFailureBonus)
      + round(|speedRatio| × speedBonus)       # speedRatio ∈ [-1, 0] for fast tests
      - round(|speedRatio| × speedPenalty)      # speedRatio ∈ (0, 1] for slow tests
      + overlapScore                             # see below
      + (overlapsChangedStaticField ? staticFieldBonus : 0)
      + round(killRate × killRateBonus)          # 0 when killRateBonus = 0 (default)

  where speedRatio = clamp(log₂(duration / median) / 3, -1, 1)
        killRate ∈ [0, 1] from mutation testing; tests without data are unaffected

  overlapScore (when coverageBonus = 0, the default):
      min(ceil(|dependencies ∩ changedClasses| / √|dependencies| × depOverlap × killMultiplier), depOverlap)
    + min(ceil(Σ complexity(dep) / √|dependencies| × changeComplexity), changeComplexity)
    where killMultiplier = killRate ≥ 0 ? (0.5 + killRate × 0.5) : 1.0

  overlapScore (when coverageBonus > 0):
      greedy set-cover bonus: coverageBonus × 0.8^rank  (rank = 0-based position in set-cover order)
```
<!-- END WEIGHTS FORMULA -->

Speed scoring uses a logarithmic scale: tests faster than the median receive a proportional bonus
(full bonus at 1/8× median), and tests slower than the median receive a proportional penalty
(full penalty at 8× median). At the median, the score contribution is zero.

## Change Complexity

The change complexity component uses Deflate compression (JDK built-in) to
estimate the information content of each changed source file. Larger compressed
sizes indicate more complex / information-dense code, which is more likely to
harbour subtle bugs after modification. Scores are normalised to [0.0, 1.0]
relative to the largest changed file, then summed over overlapping dependencies
and scaled by the weight.

## Tie-breaking (Jaccard Diversity)

Tests with equal scores are ordered using greedy Jaccard-distance selection:
the next test is chosen to maximise the **Jaccard distance** between its
dependency set and the set of dependencies already covered by previously
selected tests. This ensures breadth-first coverage — tests exercising
different parts of the codebase run before redundant ones.

Within a Jaccard tie, shorter historical duration wins, then alphabetical name.

## Failure Scoring (Exponential Decay)

Failure history uses an exponential decay model. Each time the state file is
saved **after a test run completes** (regardless of whether any tests failed):

1. All historical failure scores are multiplied by `(1 − failureDecay)`
   (default 0.3, so 70% of the score is retained per run).
2. Failures from the current run are added at full weight (+1.0 each).
3. Scores below `failurePruneThreshold` (default 0.01) are dropped.

If `save()` is called without a preceding test run (e.g. by the weight optimizer),
scores are preserved unchanged — decay represents "one test run passed"
and should not be applied spuriously.

The scorer uses `min(ceil(score), maxFailure)` to convert the decayed score
into an integer bonus, so a class that failed in the most recent run gets
the full bonus while a class that failed several runs ago gradually loses
priority.

Separate decay rates are available for class-level and method-level failures:

| Parameter | Config key | Default | Description |
|---|---|---|---|
| Failure decay | `failureDecay` | 0.3 | Per-run decay for class-level failure scores |
| Method failure decay | `methodFailureDecay` | 0.3 | Per-run decay for method-level failure scores |
| Prune threshold | `failurePruneThreshold` | 0.01 | Scores below this are dropped on save |

## Duration Smoothing

Test durations use exponential moving average (EMA) with separate alpha values
for class-level and method-level:

- **Class-level:** `durationAlpha` = 0.85 → `stored = 0.85 × measured + 0.15 × previous`
- **Method-level:** `methodDurationAlpha` = 0.85 → same formula per method

Higher alpha means more weight on the most recent measurement. This dampens
outliers while tracking trends. Both alphas are configurable in the weights
file or state file `[config]` section.

## Customizing Scores

Default weights are defined in
[`default-scoring-weights.toml`](../test-order-core/src/main/resources/default-scoring-weights.toml).
You can override them in three ways (highest priority first):

**1. System properties** — override individual weights:

```bash
mvn test -Dtestorder.score.newTest=20 -Dtestorder.score.changedTest=12
```

**2. Weights file** — provide a file with all customized weights:

```bash
mvn test -Dtestorder.weights.file=my-weights.toml
```

The file uses TOML format:

```toml
# my-weights.toml
[config]
failureDecay = 0.3
durationAlpha = 0.85

[newTest]
value = 20
range = [0, 50]

[changedTest]
value = 12
range = [0, 50]

[maxFailure]
value = 3
range = [1, 10]

[speed]
value = 2
range = [0, 10]

[speedPenalty]
value = 2
range = [0, 10]

[depOverlap]
value = 5
range = [0, 10]

[changeComplexity]
value = 2
range = [0, 5]

[staticFieldBonus]
value = 0
range = [0, 3]
```

Simple flat TOML format is also supported:

```toml
newTest = 20
changedTest = 12
maxFailure = 3
speed = 2
speedPenalty = 2
depOverlap = 5
changeComplexity = 2
staticFieldBonus = 0
coverageBonus = 0
```

**3. Maven plugin configuration** — set defaults in `pom.xml`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <configuration>
    <scoreNewTest>20</scoreNewTest>
    <scoreChangedTest>12</scoreChangedTest>
    <scoreMaxFailure>3</scoreMaxFailure>
    <scoreSpeed>2</scoreSpeed>
    <scoreSpeedPenalty>2</scoreSpeedPenalty>
    <scoreDepOverlap>5</scoreDepOverlap>
    <scoreChangeComplexity>2</scoreChangeComplexity>
    <scoreStaticFieldBonus>0</scoreStaticFieldBonus>
    <scoreCoverageBonus>0</scoreCoverageBonus>
  </configuration>
</plugin>
```

Set a bonus to `0` to disable that scoring component entirely.

## Automatic Score Tuning

Every order-mode test run records a quality snapshot to `.test-order/state.lz4`:
per-test score breakdowns, pass/fail outcomes, and the **APFD** (Average
Percentage of Faults Detected) metric — a standard measure of how early
failures were detected.

After accumulating at least 3 runs with failures, use the `optimize` command
to find weights that maximise APFD via genetic algorithm:

```bash
java -jar test-order-core-jar-with-dependencies.jar optimize .test-order/state.lz4
```

Or use the Maven plugin goal:

```bash
mvn test-order:optimize
```

The command optimises weights and saves them directly into the state file.
Subsequent test runs will automatically load the optimised weights.

Re-run periodically as your project's failure patterns evolve.

## Instrumentation Modes

| Mode | What it records | Precision | Typical overhead* | Pros | Cons |
|---|---|---|---|---|---|
| `CLASS` (default) | Method/constructor entries + foreign static-field accesses | High — class-level method/constructor/shared-state usage | Lower than full foreign-field weaving | Best default: richer signal than method-entry with less runtime drag | No per-test-method or member-level granularity |
| `METHOD` | `CLASS` + per-test-method dependency tracking | Higher — enables method-level overlap scoring | Slightly above `CLASS` | Ordering can consider which test method touches what | Slightly larger index; setup/teardown deps excluded |
| `MEMBER` | `METHOD` + member-level deps (`class#method`, `class#field`) | Highest — precise method/field impact scoring | ~121% | If a test never calls the changed method, it won't be scored | Roughly 2× the overhead of other modes; largest index |

\* Historical overhead numbers were measured on the [femtocli](https://github.com/parttimenerd/femtocli) test suite (307 unit tests, baseline ~1.1 s). A second benchmark on `spring-petclinic` is recorded below.

### Spring Petclinic Benchmark

Measured learn-run timings on `spring-petclinic` (5 measured runs per mode, baseline = pure `surefire:test` without `test-order:prepare`, `-Dcheckstyle.skip=true`, `-Dspring-javaformat.skip=true`, excluding `*IntegrationTests`, `MySqlIntegrationTests`, `PostgresIntegrationTests`, and `MysqlTestApplication`):

| Mode | Avg time | Median | Std dev | Overhead vs none |
|---|---:|---:|---:|---:|
| none | 4.926 s | 4.974 s | 0.212 s | 0.0% |
| `CLASS` | 5.553 s | 5.670 s | 0.187 s | 12.7% |
| `METHOD` | 5.473 s | 5.443 s | 0.109 s | 11.1% |
| `MEMBER` | 5.572 s | 5.445 s | 0.284 s | 13.1% |

`CLASS` is the recommended default — it keeps learn runs lighter by tracking method/constructor calls plus foreign static/shared-state access, while reserving full instance-field/member weaving for `MEMBER`.

> **Note:** This overhead only applies during **learn** runs — normal test execution (order mode) adds no instrumentation cost.
> You don't need to re-learn on every build. The dependency index stays valid until the relationship between tests and production code changes significantly (new tests, refactored call graphs, moved classes, etc.).
> The more code and test changes that accumulate since the last learn run, the less accurate the ordering becomes — the index may reference stale dependencies or miss new ones.
> This is a trade-off: frequent re-learns keep the ordering optimal but add overhead to those runs; infrequent re-learns are cheaper overall but gradually degrade ordering quality.
> A practical cadence is to re-learn after major refactors or dependency changes, and on a regular schedule (e.g. weekly or per-sprint) in CI.

## Change Detection Modes

| Mode | Default use case | Source of truth |
|---|---|---|
| `since-last-run` | Local iteration without relying on git history | LZ4 hash snapshots (`.test-order/hashes.lz4`) |
| `since-last-commit` | CI or branch workflows comparing against latest commit | `git diff HEAD~1..HEAD` plus uncommitted overlay |
| `uncommitted` | Run tests for current workspace edits | staged + unstaged + untracked files |
| `explicit` | Scripted/manual targeting | `-Dtestorder.changed.classes=...` |

Default mode is `uncommitted`, which detects staged, unstaged, and untracked file changes in your working tree.
You can override this with `-Dtestorder.changeMode=<mode>` or configure `auto` to fall back through:
- `explicit` when `testorder.changed.classes` is provided
- `since-last-run` if snapshots exist
- `since-last-commit` otherwise

## Package Detection

The plugin **automatically scans `src/main/java`** to detect the top-level source
packages and uses them as the instrumentation filter. This means zero
configuration is needed in most cases — the agent instruments exactly the
classes that live in your project.

If you need to instrument additional packages (e.g. a library you want to
track), use `includePackages` — the specified prefixes are **merged** with the
auto-detected source packages:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.includePackages=org.lib.extra,com.other
```

Redundant prefixes are automatically removed: if both `com.example` and
`com.example.app` appear, only `com.example` is kept.

When no source directories exist (e.g. a BOM-only project) and
`filterByGroupId=true` (default), the Maven `groupId` is used as a fallback.

## Method-level Scoring

Within each test class, methods can be reordered to surface failing methods
earlier. This is opt-in via:

```bash
mvn test -Dtestorder.methodOrder.enabled=true
```

Or in plugin config:

```xml
<configuration>
  <methodOrder>true</methodOrder>
</configuration>
```

Method scoring considers:

| Component | Default weight | Description |
|---|---|---|
| **Failure recency** | 3.0 | Methods that failed recently run first |
| **New method** | 5.0 | Methods with no telemetry history (untested = risky) |
| **Changed method** | 3.0 | Methods whose source code changed |
| **Speed (fast)** | 1.0 | Fast methods get proportional bonus (logarithmic, class-local median) |
| **Speed (slow)** | 1.0 | Slow methods get proportional penalty (logarithmic, class-local median) |
| **Dep overlap** | 2.0 | Per-method dependency overlap with changed classes |

Speed thresholds are **class-local** — a method's duration is compared against
the median of all methods within its class, not a global median.

If no method-level telemetry is available (first run or no failures), methods
keep their source order.

### Method-level Scoring Overrides

When method-level ordering is enabled, individual method scoring components can be tuned:

| Property | Description |
|---|---|
| `testorder.method.score.failureRecency` | Method failure recency weight |
| `testorder.method.score.newMethod` | New method bonus |
| `testorder.method.score.changedMethod` | Changed method bonus |
| `testorder.method.score.fast` | Fast method bonus |
| `testorder.method.score.slow` | Slow method penalty |
| `testorder.method.score.depOverlap` | Per-method dependency overlap |
| `testorder.method.score.coverageBonus` | Per-method coverage bonus |

## ML-Enhanced Scoring

When ML predictions are available (`testorder.ml.enabled=true` and 5+ recorded runs),
the `show` and `dashboard` goals display a **P(fail)** column alongside the standard scoring.

ML predictions complement the deterministic scoring system — they do not replace it.
The standard score determines test execution order, while P(fail) provides an
additional signal about test reliability over time.

See [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md#ml-failure-predictions) for configuration details.
