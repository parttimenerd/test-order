# test-order Cheat Sheet

Quick reference for the most common commands. For full details see the
[CLI Reference](CLI_REFERENCE.mdx) or [Maven Plugin](MAVEN_PLUGIN.md) docs.

---

## Plugin setup (Maven)

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.1.0</version>
  <extensions>true</extensions>   <!-- required -->
  <executions>
    <execution>
      <goals><goal>prepare</goal></goals>
    </execution>
  </executions>
</plugin>
```

Add to `~/.m2/settings.xml` once so `test-order:` prefix works:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>me.bechberger</pluginGroup>
  </pluginGroups>
</settings>
```

## Plugin setup (Gradle)

```groovy
// settings.gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// build.gradle
plugins { id 'me.bechberger.test-order' version '0.1.0' }
```

---

## Daily development (Maven)

| What | Command |
|------|---------|
| Normal run (auto learn/order) | `mvn test` |
| Run only affected tests | `mvn test-order:affected test` |
| Then run deferred tests | `mvn test-order:run-remaining test` |
| Force re-learn (after big refactor) | `mvn test -Dtestorder.mode=learn` |
| Skip test-order entirely | `mvn test -Dtestorder.skip=true` |

## Daily development (Gradle)

| What | Command |
|------|---------|
| Normal run (auto learn/order) | `./gradlew test` |
| Run only affected tests | `./gradlew testOrderAffected` |
| Then run deferred tests | `./gradlew testOrderRunRemaining` |
| Force re-learn (after big refactor) | `./gradlew test -Dtestorder.mode=learn` |
| Skip test-order entirely | `./gradlew test -Dtestorder.skip=true` |

---

## Inspect and debug (Maven / Gradle equivalent)

| What | Maven | Gradle |
|------|-------|--------|
| Show test ranking and scores | `mvn test-order:show` | `./gradlew testOrderShow` |
| Explain score for one test | `mvn test-order:explain -Dtestorder.explain.test=com.Foo` | `./gradlew testOrderExplain -Ptest=com.Foo` |
| Interactive HTML dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Live dashboard (auto-refresh) | `mvn test-order:serve` | `./gradlew testOrderServe` |
| Health check / diagnose setup | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| What changed (dry-run) | `mvn test-order:show -Dtestorder.debug=true` | same |
| List all goals | `mvn test-order:help` | `./gradlew testOrderHelp` |

---

## CI — fast feedback loop

**Single-job with caching (minimal, any CI):**
```yaml
# Restore .test-order/ from cache before 'mvn test'
# Save .test-order/ to cache after (even on failure)
```

**Two-phase (fail fast):**
```bash
mvn test-order:affected test          # fast: only affected tests
mvn test-order:run-remaining test     # slow: everything else (only if phase 1 passes)
```

**Three-tier CI (tiered):**
```bash
# Tier 1: affected tests only
mvn test-order:tiered-select test -Dtestorder.changeMode=since-last-commit

# Tier 2: top-scored remaining
mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2

# Tier 3: everything else
mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
```

For full YAML examples: [ci-examples/](https://github.com/parttimenerd/test-order/tree/main/docs/ci-examples)

---

## Key properties

| Property | Default | Description |
|----------|---------|-------------|
| `testorder.mode` | `auto` | `auto` / `learn` / `order` / `skip` |
| `testorder.changeMode` | `uncommitted` | `auto` / `uncommitted` / `since-last-commit` / `since-last-run` / `explicit` |
| `testorder.changed.classes` | — | Explicit changed class FQCNs (comma-separated; use with `changeMode=explicit`) |
| `testorder.instrumentation.mode` | `MEMBER` | `CLASS` / `METHOD` / `MEMBER` — trade-off between accuracy and overhead |
| `testorder.skip` | `false` | Skip test-order entirely |
| `testorder.debug` | `false` | Verbose output showing change detection and ordering decisions |
| `testorder.autoLearnRunThreshold` | `10` | Force re-learn after N order-mode runs |
| `testorder.affected.topN` | `-1` | Max number of tests for `affected` goal (`-1` = all affected) |
| `testorder.flaky.retries` | `0` | Retry FLAKY-classified tests on failure (recommended: `2`). See [FLAKY_AND_CACHING.mdx](FLAKY_AND_CACHING.mdx). |
| `testorder.flaky.quarantine` | `false` | Report FLAKY-test failures as aborted (skipped) instead of failed |
| `testorder.cache.skipUnchanged` | `false` | Skip tests whose deps are unchanged and that passed the last N runs |
| `testorder.cache.minPassStreak` | `3` | Required consecutive pass streak before cache eligibility |
| `testorder.auto.alwaysLearn` | `false` | Always attach learn agent in `auto` mode (pair with `selective` for low-overhead incremental index updates) |
| `testorder.learn.selective` | `false` | Instrument only changed classes + transitive callees — keeps learn overhead proportional to change size |

---

## Troubleshooting quick-fixes

**First step — always run:** `mvn test-order:diagnose`  
It checks index health, permissions, package filters, and prints actionable fix steps.

| Symptom | Fix |
|---------|-----|
| "Wrote fallback payloads" every run | Add `<extensions>true</extensions>` to the plugin block in `pom.xml` |
| Tests always in default order | Check `.test-order/test-dependencies.lz4` exists; re-run `mvn test` |
| `No plugin found for prefix 'test-order'` | Add `me.bechberger` to `<pluginGroups>` in `~/.m2/settings.xml` |
| All scores 0, no reordering | Run with `-Dtestorder.debug=true` — likely no changed classes detected |
| Empty or tiny index after learn | Set `-Dtestorder.includePackages=com.yourpackage` (package filter too narrow) |
| JaCoCo reports 0% coverage | Change Surefire `<argLine>` to `@{argLine}` |
| `Failed to execute auto workflow` | Run `mvn test-order:diagnose`; check permissions on `.test-order/` |
| Tests skipped unexpectedly | Cold-start without index — `affected`/tiered goals fall back to all tests; run `mvn test` first |
| Every test lands in tier-1 in CI | Shallow clone hides `HEAD~1`. On GitLab set `variables: { GIT_DEPTH: 0 }`; on GitHub Actions use `actions/checkout@v4` with `fetch-depth: 0`; or use `-Dtestorder.changeMode=since-last-run`. |
| Learn run completes but tests still run in alphabetical order | `testorder.skip=true` is set, or the JUnit/TestNG extension isn't on the test classpath — check `-Dtestorder.debug=true` output; ensure no `-Dtestorder.skip=true` in CI env vars or `pom.xml`; verify `test-order-junit` or `test-order-testng` is on the test classpath |
| "Binary read error" or index appears corrupt | Partial CI cache restore or interrupted learn run left a truncated `.lz4` file — `rm -rf .test-order/ && mvn test` to force a clean learn run |
| Tests start failing in new/unexpected order after enabling test-order | Pre-existing isolation bug (shared static state, temp files, DB rows) exposed by reordering — run `mvn test -Dtestorder.shuffle=true` to reproduce, then see [DETECT_DEPENDENCIES.md](DETECT_DEPENDENCIES.md) |
| `-Dtest=...` filter active, but no auto-selection / reordering happening | Intentional: test-order skips auto-selection when `-Dtest` is set and delegates to Surefire (see INFO log). Use `-Dtestorder.mode=order` with `-Dtest` for ordering-only. |

**Nuclear reset:** `rm -rf .test-order && mvn test -Dtestorder.mode=learn`

---

MIT licensed — see [LICENSE](https://github.com/parttimenerd/test-order/blob/main/LICENSE)
