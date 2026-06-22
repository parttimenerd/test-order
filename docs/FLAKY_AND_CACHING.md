# Flaky-Test Handling and Skip-if-Unchanged Cache

test-order ships three opt-in features that close the remaining gap to commercial
"predictive test selection" tooling (e.g. Gradle Develocity):

| Feature | Property | What it does |
|---|---|---|
| **Auto-retry FLAKY tests** | `testorder.flaky.retries=N` | When a test classified `FLAKY` by the ML report fails, retry it up to *N* times before reporting failure. |
| **Quarantine FLAKY failures** | `testorder.flaky.quarantine=true` | After retries are exhausted, downgrade `FLAKY` failures to **aborted** (skipped) instead of failed. The build stays green during your flakiness-rollout window. |
| **Skip-if-unchanged cache** | `testorder.cache.skipUnchanged=true` | Skip tests whose covered source classes are unchanged **and** that have passed the last *N* runs. They are omitted from the run entirely. |

All three are independent — you can enable any combination — and all default to **off**.

## Decision matrix

| Situation | Recommended flags |
|---|---|
| First CI runs (no history) | leave defaults |
| Suite with known intermittent failures | `testorder.flaky.retries=2` |
| Same suite, but you can't afford red builds during the cleanup window | `testorder.flaky.retries=2 -Dtestorder.flaky.quarantine=true` |
| Long green streak, want to cut CI time | `testorder.cache.skipUnchanged=true` |
| Combine everything for max-speed CI | all three flags on |

## Feature 1 — Auto-retry FLAKY tests

```
mvn verify \
  -Dtestorder.flaky.retries=2 \
  -Djunit.jupiter.extensions.autodetection.enabled=true
```

### Eligibility

A test method is eligible for retry **only** when its declaring class is classified
`FLAKY` in `.test-order/ml-report.txt`. Tests classified `HEALTHY`, `DEGRADING`, or
`FAILING` are **never** retried — those failures are either real regressions
(`FAILING`/`DEGRADING`) or successes that haven't yet shown instability.

The classification comes from the ML health analyzer (run `mvn test-order:show` or
the dashboard to inspect it). Build up history first (~5 runs minimum), then turn
retries on.

### How retries are performed

`FlakyRetryExtension` is a JUnit Jupiter
[`InvocationInterceptor`](https://junit.org/junit5/docs/current/api/org.junit.jupiter.api/org/junit/jupiter/api/extension/InvocationInterceptor.html).
On first attempt failure, it re-invokes the method by reflection up to `retries`
times. The **final** outcome (pass after retry, or fail after exhausting retries)
is what JUnit sees — so existing reporters and CI status checks are unaffected.

Retry counts per test class are recorded in
`<build-dir>/.test-order/flaky-runtime.txt` and surfaced in the CI summary and
dashboard.

#### Lifecycle caveat

Retries happen inside a single `interceptTestMethod` invocation, so JUnit
Jupiter fires `@BeforeEach` and `@AfterEach` only around the **outer** test —
each retry reuses the same fixture instance and does not re-fire callback
extensions or parameter resolvers. This matches the single-shot
`Invocation.proceed()` contract; the same constraint applies to JUnit
Pioneer's `@RetryingTest` when it falls back to interception. If your flaky
test relies on per-attempt fixture freshness (e.g. a counter or a freshly
opened resource that `@BeforeEach` sets up), move that setup into the test
body or use a `@TestTemplate`-based retry pattern instead.

### Activation

The extension uses JUnit's service-loader auto-detection. Add **one line** to your
`junit-platform.properties` (or pass `-D`):

```
junit.jupiter.extensions.autodetection.enabled=true
```

Auto-detection registers any extension declared in
`META-INF/services/org.junit.jupiter.api.extension.Extension` — the test-order
runtime JAR contains this entry.

### Framework support

| Framework | Status |
|---|---|
| JUnit Jupiter 5.5+ | **supported** |
| JUnit Jupiter 6.x  | **supported** (5/6 share the extension API) |
| JUnit Vintage (JUnit 4 via Platform) | not retried — failures propagate normally |
| TestNG / Spock | not supported (initial release) |

JUnit 4 retry is tracked as a follow-up. Tests classified `FLAKY` in vintage
suites still appear in the dashboard and CI summary; they simply aren't retried.

## Feature 2 — Quarantine FLAKY failures

```
mvn verify \
  -Dtestorder.flaky.retries=2 \
  -Dtestorder.flaky.quarantine=true \
  -Djunit.jupiter.extensions.autodetection.enabled=true
```

When `testorder.flaky.quarantine=true`, a `FLAKY`-classified test that still
fails after all retries throws `org.opentest4j.TestAbortedException`. JUnit
treats this exactly like `Assumptions.assumeTrue(false)` — the test is reported
as **aborted** (skipped), not failed, and the build stays green.

Quarantined classes are recorded in `<build-dir>/.test-order/flaky-runtime.txt`
and surface in the CI summary and dashboard's ML tab so the team has explicit
visibility.

> **Use quarantine deliberately.** It's intended as a short-term rollout safety
> net while you fix the underlying flakiness, not a permanent state. Track
> quarantined tests as bugs.

### Quarantine and the pass-streak cache

Quarantined runs are **neutral** for pass-streak accounting. Because a
quarantined test throws `TestAbortedException` (not a failure), it would
otherwise look like a passing run and inflate the streak that drives the
skip-if-unchanged cache (Feature 3). Test-order excludes quarantined classes
from the recorded run outcomes entirely — exactly the same way the cache-skip
path records a neutral run for tests it skipped. The net effect: a class that
spends a few runs quarantined doesn't get fast-tracked into the cache the
moment ML reclassifies it as HEALTHY.

## Feature 3 — Skip-if-unchanged cache

```
mvn verify \
  -Dtestorder.cache.skipUnchanged=true \
  -Dtestorder.cache.minPassStreak=3
```

### How it works

After the normal selection logic (new tests, change-affected tests, top-N
scored, diverse-fast random) has picked the run, test-order looks at every
remaining test class and asks:

1. Is this test in `@AlwaysRun`? **Don't skip.**
2. Did any of this test's covered source classes change? **Don't skip.**
3. Is the test new (not in the dependency index)? **Don't skip.**
4. Has this test passed every one of the last `minPassStreak` runs? If yes, **skip.**

Skipped tests are reported as `cached` in `Selection.cached()` and are
**omitted from both the selected and remaining lists** — they don't run at all
this invocation.

### Safety cap (`maxSkipFraction`)

Even if a long green streak makes every test eligible, the cache will never skip
more than `maxSkipFraction × total tests` (default `0.9`). When the cap binds,
**slower tests are preferred for skipping** so cache savings are maximized.

Set `testorder.cache.maxSkipFraction=1.0` to disable the cap entirely.

### When skipped tests are still beneficial to run

The cache is intentionally conservative — but for paranoid suites you can set
`testorder.cache.minPassStreak=10` to require a much longer green streak before
skipping, or schedule a separate nightly job with `testorder.cache.skipUnchanged=false`
to run the full suite.

## CI summary integration

When `testorder.ci.summary=true` is set, the existing CI summary
(`target/test-order-summary.md`, `.json`, and `-selection-report.xml`)
gains three new rows:

```
| **Cached (skipped, unchanged)** | 42 (saved ~3m12s) |
| **Retried (flaky)**             | 5 |
| **Quarantined**                 | 2 |
```

The JSON output adds `cachedCount`, `cachedTimeSavedMs`, `cachedTests`,
`retriedCount`, `quarantinedCount`, and `quarantinedTests` fields. The
JUnit-XML selection report adds `<testcase classname="cached" ...>` and
`<testcase classname="quarantined" ...>` entries with `<skipped/>` tags.

## Dashboard integration

The dashboard's **ML Health** tab gains "Retries (this run)" and
"Quarantined" columns. A new **Cache** tab lists the tests skipped this run,
their pass streak, and the estimated time saved.

When `testorder.cache.skipUnchanged=true` is set but no tests were skipped on
the current run (for example: every eligible test had a source change, or no
test has yet built up the required pass streak), the Cache tab shows an
**"enabled but no tests were skipped this run"** message instead of the
"cache is disabled" message reserved for runs where the feature is off.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `testorder.flaky.retries=2` but my flaky test still fails on first failure | JUnit auto-detection is disabled | Add `junit.jupiter.extensions.autodetection.enabled=true` to `junit-platform.properties` |
| FLAKY test is retried but the ML report doesn't classify it as FLAKY | ML report is stale or missing | Run `mvn test-order:show` to verify; build up ~5+ runs of history first |
| Cache skips a test I want to always run | Test isn't in `@AlwaysRun` | Annotate the test class with `@AlwaysRun` (from `test-order-annotations`) |
| Cache skips too aggressively | `minPassStreak` too low | Raise to `5` or `10` for stricter eligibility |
| Want to disable cache for one CI job | per-invocation override | Pass `-Dtestorder.cache.skipUnchanged=false` for that job |

## See also

- [CLI_REFERENCE.md](CLI_REFERENCE.md) — full property list
- [SCORING.md](SCORING.md) — how the underlying score is computed
- [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md) — Maven configuration examples
