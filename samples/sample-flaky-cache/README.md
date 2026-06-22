# sample-flaky-cache

End-to-end smoke sample for the three Develocity-parity features added to
`test-order`:

1. **Flaky auto-retry** (`testorder.flaky.retries`) — JUnit Jupiter
   `InvocationInterceptor` retries tests classified `FLAKY` by the ML report.
2. **Flaky quarantine** (`testorder.flaky.quarantine`) — final retry failures
   for FLAKY tests are downgraded to `TestAbortedException` so they show as
   aborted, not failed.
3. **Skip-if-unchanged caching** (`testorder.cache.skipUnchanged`) — a test
   that (a) has no changed dependencies and (b) has passed N consecutive runs
   is excluded from the run entirely.

## Layout

- `src/main/java/com/example/{Greeting,PriceService}.java` — trivial source classes the tests cover.
- `src/test/java/com/example/{FlakyServiceTest,GreetingTest,PriceServiceTest}.java` — three test classes.
  `FlakyServiceTest` uses a `target/flaky-counter` file so its failure pattern
  is deterministic and survives retries within the same JVM.
- `src/test/resources/junit-platform.properties` — enables JUnit Jupiter
  extension auto-detection so `FlakyRetryExtension` (in the test-order-junit
  runtime JAR) gets ServiceLoader-registered.
- `seed/ml-report.txt` — pre-seeded ML report classifying
  `FlakyServiceTest` as `FLAKY` so retry/quarantine actually fire. Copy it
  into `.test-order/ml-report.txt` before running the sample (`.test-order/`
  is gitignored, so it lives in `seed/` in the repo).

## Try it

Build the plugin first:

```bash
mvn -pl test-order-core,test-order-junit,test-order-maven-plugin -am install -DskipTests
```

Seed the ML report so the FLAKY classification is in place:

```bash
mkdir -p .test-order && cp seed/ml-report.txt .test-order/ml-report.txt
```

Then from this directory:

```bash
# 1. Auto-retry — FlakyServiceTest fails attempt 0, retry recovers
rm -f target/flaky-counter .test-order/state.lz4
mvn test -Dsample.flaky.failUntil=1 -Dtestorder.flaky.retries=2

# 2. Quarantine — FlakyServiceTest fails forever, aborted not failed
rm -f target/flaky-counter
mvn test -Dsample.flaky.failUntil=99 -Dtestorder.flaky.retries=2 \
  -Dtestorder.flaky.quarantine=true

# 3. Skip-if-unchanged — build a 3-run pass streak, then verify cache fires
rm -f .test-order/state.lz4 target/flaky-counter
for i in 1 2 3 4; do mvn -q test; done
mvn test -Dtestorder.cache.skipUnchanged=true -Dtestorder.cache.minPassStreak=3 \
  -Dtestorder.auto.runRemaining=false
# Expect: "[test-order] Cache: skipped N unchanged test(s) ..." and fewer tests run.
# The -Dtestorder.auto.runRemaining=false flag is required because the default
# (true) bypasses the include filter and runs every test in the index regardless
# of the selection result. With it off, only the selected (non-cached) tests run.
```
