# Scoring System

Each test class receives a **priority score**. Tests run in descending score order; among ties, faster tests go first.

## Scoring Formula

$$
\text{score}(t) =
  \underbrace{[t\text{ is new}]\cdot w_{\text{new}}}_{\text{+15}}
+ \underbrace{[t\text{ source changed}]\cdot w_{\text{changed}}}_{\text{+9}}
+ \underbrace{\min\!\bigl(\lceil F(t)\rceil,\, w_{\text{fail}}\bigr)}_{\text{failure recency, 0..5}}
+ \underbrace{\text{overlap}(t)}_{\text{dep overlap, 0..5}}
+ \underbrace{\text{complexity}(t)}_{\text{change complexity, 0..2}}
+ \underbrace{[t\text{ same pkg}]\cdot w_{\text{pkg}}}_{\text{+2}}
+ \underbrace{\text{speed}(t)}_{\pm 1}
$$

**Sub-expressions:**

$$
F(t) = \sum_{i} e_i \cdot (1 - d)^{\,r_i}
\qquad d = 0.3,\quad r_i = \text{runs since failure } i
$$

$$
\text{overlap}(t) = \min\!\left(\left\lceil \frac{|\,\text{deps}(t)\cap\Delta\,|}{\sqrt{\max(|\text{deps}(t)|,\,5)}} \cdot w_{\text{overlap}} \right\rceil,\; w_{\text{overlap}}\right)
\qquad \Delta = \text{changed classes}
$$

$$
\text{speed}(t) = \operatorname{clamp}\!\left(\frac{\log_2(d_t / \tilde{d})}{3},\,-1,\,1\right)
\qquad d_t = \text{test duration},\quad \tilde{d} = \text{suite median}
$$

Full speed bonus at $d_t \le \tilde{d}/8$; full penalty at $d_t \ge 8\tilde{d}$; zero at the median.

> **All weights are configurable.** Override any signal via system properties (`-Dtestorder.score.*=…`) or a [TOML weights file](#customizing-scores).

## Score Components

| Signal | Default | Config key | Notes |
|---|:---:|---|---|
| **New test** | **15** | `testorder.score.newTest` | Any test not yet in the dependency index |
| **Changed test** | **9** | `testorder.score.changedTest` | Test source file itself was modified |
| **Failure recency** | **5** max | `testorder.score.maxFailure` | Exponential decay — see [below](#failure-scoring-exponential-decay) |
| **Dep overlap** | **5** max | `testorder.score.depOverlap` | $\sqrt{}$-normalised count of changed classes the test exercises |
| **Change complexity** | **2** max | `testorder.score.changeComplexity` | Deflate-entropy of changed files; disabled when `coverageBonus > 0` |
| **Package proximity** | **2** | `testorder.score.packageProximityBonus` | Test package matches a changed class's package |
| **Speed bonus** | **1** | `testorder.score.speed` | Logarithmic; full at $d_t \le \tilde{d}/8$ |
| **Speed penalty** | **−1** | `testorder.score.speedPenalty` | Logarithmic; full at $d_t \ge 8\tilde{d}$ |
| **Static field** | 0 | `testorder.score.staticFieldBonus` | Opt-in; requires `MEMBER` instrumentation mode |
| **Coverage bonus** | 0 | `testorder.score.coverageBonus` | Opt-in; replaces dep overlap with greedy set-cover ($\times 0.8^{\,\text{rank}}$) |
| **Kill-rate** | 0 | `testorder.score.killRateBonus` | Opt-in; scaled by PIT mutation kill rate |

Set any bonus to `0` to disable that signal entirely.

## Change Complexity

Uses Deflate compression (JDK built-in) as an entropy proxy: larger compressed sizes indicate denser, more bug-prone changes. Scores are normalised to $[0,1]$ relative to the largest changed file, summed over overlapping dependencies, then scaled by $w_{\text{changeComplexity}}$.

Disabled automatically when `coverageBonus > 0`.

## Tie-breaking (Jaccard Diversity)

Equal-scored tests are ordered by **greedy Jaccard-distance selection**: each next test maximises the Jaccard distance between its dependency set and the union of dependencies already covered. This ensures breadth-first codebase coverage before redundant tests.

$$
J_{\text{dist}}(A,B) = 1 - \frac{|A \cap B|}{|A \cup B|}
$$

Within a Jaccard tie: shorter historical duration wins, then alphabetical name.

## Failure Scoring (Exponential Decay)

Each time the state file is saved after a completed test run:

$$
\text{score}_{\text{fail}}^{(n+1)}(t) = \text{score}_{\text{fail}}^{(n)}(t) \cdot (1 - d) + [\text{failed in run } n]
\qquad d = 0.3
$$

Entries below `failurePruneThreshold` (default 0.01) are dropped. The final bonus is $\min(\lceil \text{score}_{\text{fail}} \rceil,\, w_{\text{fail}})$.

| Parameter | Config key | Default |
|---|---|:---:|
| Decay per run | `failureDecay` | 0.3 |
| Method-level decay | `methodFailureDecay` | 0.3 |
| Prune threshold | `failurePruneThreshold` | 0.01 |

> **Note:** Decay only applies when a test run completes. Calling `save()` without a preceding run (e.g. from the weight optimizer) preserves scores unchanged.

## Duration Smoothing (EMA)

Durations are smoothed with an exponential moving average to dampen outliers:

$$
d_t^{(n+1)} = \alpha \cdot d_t^{\text{measured}} + (1-\alpha) \cdot d_t^{(n)}
\qquad \alpha = 0.85
$$

Both class-level (`durationAlpha`) and method-level (`methodDurationAlpha`) default to 0.85. Higher $\alpha$ tracks recent measurements more aggressively.

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

### When to tune — and where to start

**For most projects the defaults work well.** The components worth tuning, in order of impact:

| Component | Default | Raise when… | Lower when… |
|---|---|---|---|
| `newTest` | 10 | New tests are frequently missed (team adds many tests per sprint) | New tests consistently pass; you want to de-prioritise them |
| `depOverlap` | 5 | Change-detection is precise and you trust the index | Many tests share the same broad dependency set (large integration tests) |
| `changeComplexity` | 2 | Complex multi-file refactors often introduce regressions | Almost all changes are single-class; complexity signal is noisy |
| `speedPenalty` | 2 | Slow tests are rarely responsible for failures | You have a few very slow tests that *do* catch real regressions |
| `maxFailure` | 3 | Recent failures are a strong predictor (high-churn codebase) | Failures are rare and clustered in old code you're cleaning up |

**Interpreting APFD:** An APFD of 1.0 means every failing test ran before every passing test. For most projects, 0.7–0.85 with defaults is typical; above 0.85 suggests the defaults are already well-matched to your failure patterns. If APFD is below 0.6 consistently, run `mvn test-order:optimize` (see below) rather than tuning manually.

## Automatic Score Tuning

Every order-mode test run records a quality snapshot to `.test-order/state.lz4`:
per-test score breakdowns, pass/fail outcomes, and the **APFD** (Average
Percentage of Faults Detected) metric — a standard measure of how early
failures were detected.

After accumulating at least 5 runs with failures, use the `optimize` command
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

> **If your test suite rarely or never fails in CI:** The optimizer needs
> failure data to work with — it cannot improve APFD without observed failures.
> In this case stick with the defaults (or the manual tuning table above).
> You can still benefit from test-order's ordering: tests that cover recently
> changed code run first even when they pass, giving faster feedback on
> change-related behaviour before the rest of the suite finishes.

## Instrumentation Modes

Three modes trade index precision against learn-run overhead:

| Mode | Records | Overhead† | Notes |
|---|---|:---:|---|
| `CLASS` | Method/constructor entries + foreign static-field accesses | ~13% | Lighter learns; good starting point |
| `METHOD` | `CLASS` + per-test-method dependency tracking | ~11% | Enables method-level overlap scoring |
| **`MEMBER` (default)** | `METHOD` + exact method/field access per test | ~13% | Highest precision; ~2× index size |

†Overhead on `spring-petclinic` learn runs (baseline 4.93 s, 5 runs each):

| Mode | Mean | Median | Std dev |
|---|---:|---:|---:|
| none | 4.93 s | 4.97 s | 0.21 s |
| `CLASS` | 5.55 s | 5.67 s | 0.19 s |
| `METHOD` | 5.47 s | 5.44 s | 0.11 s |
| `MEMBER` | 5.57 s | 5.45 s | 0.28 s |

> **Overhead only applies to learn runs.** Normal ordered test execution (`auto` mode) adds **no** instrumentation cost. Re-learn after major refactors or on a weekly CI schedule — not every build.

## Change Detection Modes

| Mode | When to use | Source of truth |
|---|---|---|
| `uncommitted` *(default)* | Local iteration — catch your current edits | Staged + unstaged + untracked files |
| `since-last-run` | Local iteration without git | LZ4 hash snapshots (`.test-order/hashes.lz4`) |
| `since-last-commit` | CI / branch workflows | `git diff HEAD~1..HEAD` + uncommitted overlay |
| `explicit` | Scripted targeting | `-Dtestorder.changed.classes=pkg.Foo,pkg.Bar` |

`auto` selects: `since-last-run` (if a hash snapshot exists) → `since-last-commit`.

Override with `-Dtestorder.changeMode=<mode>`.

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

Opt-in reordering of test methods within each class. Enable with `-Dtestorder.methodOrder.enabled=true` or `<methodOrder>true</methodOrder>` in plugin config.

The method score uses the same exponential-decay failure model as class scoring, with class-local speed medians:

| Signal | Default weight |
|---|:---:|
| New method (no telemetry history) | **5.0** |
| Changed method | **3.0** |
| Failure recency | **3.0** |
| Dep overlap | 2.0 |
| Speed bonus | 1.0 |
| Speed penalty | −1.0 |

Speed thresholds are **class-local** — each method's duration is compared to the median of all methods within its own class.

Fine-tune individual weights via `testorder.method.score.<signal>` (e.g. `testorder.method.score.failureRecency=5`).

## ML-Enhanced Scoring

When ML predictions are available (`testorder.ml.enabled=true` and 5+ recorded runs),
the `show` and `dashboard` goals display a **P(fail)** column alongside the standard scoring.

ML predictions complement the deterministic scoring system — they do not replace it.
The standard score determines test execution order, while P(fail) provides an
additional signal about test reliability over time.

See [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md#ml-failure-predictions) for configuration details.

## Selective Learn (`testorder.learn.selective`)

When `testorder.learn.selective=true`, the learn agent only re-instruments classes
**reachable** from the current changes: changed classes plus their transitive callees
up to 4 hops in the static call graph. This keeps per-run instrumentation overhead
proportional to the size of your change rather than the size of the project.

```bash
# Maven
mvn test -Dtestorder.mode=learn -Dtestorder.learn.selective=true

# Gradle
./gradlew test -Dtestorder.mode=learn -Dtestorder.learn.selective=true
```

Combine with `alwaysLearn` for low-overhead background index maintenance on every
ordered run:

```bash
mvn test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true
./gradlew test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true
```

Both flags default to `false`. When `alwaysLearn=true` and no structural changes are
detected (empty uncertain set), the agent attach is skipped — zero overhead on no-change
runs.

> **Note:** Selective learn is most effective when your change set is small relative to
> the total project size. For large-scale refactors, a full learn run (`selective=false`)
> produces a more complete dependency index.
