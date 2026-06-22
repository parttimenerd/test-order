# sample-flaky-cache-gradle

Gradle counterpart to `samples/sample-flaky-cache`. Exercises the same three
features against the Gradle plugin:

1. **Flaky auto-retry** (`testorder.flaky.retries`)
2. **Flaky quarantine** (`testorder.flaky.quarantine`)
3. **Skip-if-unchanged caching** (`testorder.cache.skipUnchanged`)

## Try it

Publish the plugin to the local Maven repo first:

```bash
mvn -pl test-order-core,test-order-junit,test-order-annotations -am install -DskipTests
(cd test-order-gradle-plugin && ./gradlew publishToMavenLocal)
```

Seed the ML report:

```bash
mkdir -p .test-order && cp seed/ml-report.txt .test-order/ml-report.txt
```

Then from this directory:

```bash
# 1. Auto-retry
rm -f build/flaky-counter .test-order/state.lz4
gradle test --rerun-tasks \
  -Dsample.flaky.failUntil=1 \
  -Dtestorder.flaky.retries=2

# 2. Quarantine
rm -f build/flaky-counter
gradle test --rerun-tasks \
  -Dsample.flaky.failUntil=99 \
  -Dtestorder.flaky.retries=2 \
  -Dtestorder.flaky.quarantine=true

# 3. Skip-if-unchanged
#    NOTE: cache wiring on the Gradle plugin side currently passes the cached
#    set into Test.setIncludes() but the cache-skip log line is emitted at the
#    boundary that already varies between learn and order phases. See the
#    Maven sample for the canonical end-to-end demo of the cache feature.
rm -f .test-order/state.lz4 build/flaky-counter
for i in 1 2 3 4; do gradle test --rerun-tasks; done
gradle test --rerun-tasks \
  -Dtestorder.cache.skipUnchanged=true \
  -Dtestorder.cache.minPassStreak=3 \
  -Dtestorder.auto.runRemaining=false
```
