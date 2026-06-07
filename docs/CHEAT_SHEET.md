# test-order Cheat Sheet

Quick reference for the most common commands. For full details see the
[CLI Reference](CLI_REFERENCE.md) or [Maven Plugin](MAVEN_PLUGIN.md) docs.

---

## Plugin setup (Maven)

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>0.0.1-SNAPSHOT</version>
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
    repositories { mavenLocal(); gradlePluginPortal() }
}

// build.gradle
plugins { id 'me.bechberger.test-order' version '0.0.1-SNAPSHOT' }
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
| Explain score for one test | `mvn test-order:explain -Dtestorder.explain.test=com.Foo` | — |
| Interactive HTML dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Live dashboard (auto-refresh) | `mvn test-order:serve` | — |
| Health check / diagnose setup | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| What changed (dry-run) | `mvn test-order:show -Dtestorder.debug=true` | same |
| List all goals | `mvn test-order:help` | — |

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

For full YAML examples: [ci-examples/](ci-examples/)

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

Nuclear reset: `rm -rf .test-order && mvn test -Dtestorder.mode=learn`

Nuclear option: `rm -rf .test-order && mvn test -Dtestorder.mode=learn`
