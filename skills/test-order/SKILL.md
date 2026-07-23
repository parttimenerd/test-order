---
name: test-order
description: Run, prioritise, and select tests with the test-order plugin (Maven and Gradle). Use when the user wants to run only affected tests, see which tests will run first, recover from "no dependency index" errors, or interpret test-order output. Covers help discovery via JSON manifest, learn vs order modes, and the Run:-line error-recovery convention.
---

# test-order skill

[test-order](https://github.com/parttimenerd/test-order) reorders and selects tests based on a learned dependency index. First run learns; subsequent runs prioritise tests that cover changed code.

## Installation

Check [Maven Central](https://central.sonatype.com/artifact/me.bechberger/test-order-maven-plugin) for the latest version before inserting snippets.

**Maven** — add to `<build><plugins>` in `pom.xml`:
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>test-order-maven-plugin</artifactId>
  <version>LATEST</version>
  <extensions>true</extensions>
  <executions><execution><goals><goal>prepare</goal></goals></execution></executions>
</plugin>
```
For the short `test-order:` CLI prefix, add `<pluginGroup>me.bechberger</pluginGroup>` to `~/.m2/settings.xml`; otherwise use `mvn me.bechberger:test-order-maven-plugin:<goal>`.

**Gradle** — plugin is on the Gradle Plugin Portal:
```kotlin
plugins { id("me.bechberger.test-order") version "LATEST" }
```

Add `.test-order/`, `target/test-order-dashboard/`, `build/test-order-dashboard/` to `.gitignore`.

---

## When to fetch the manifest

The common tasks below cover the stable surface. Only fetch the manifest when you need a goal not listed here or want to verify stability/JSON support for an unfamiliar task:

```bash
mvn -B -ntp -q test-order:help -Dtestorder.help.format=json   # Maven
./gradlew --quiet testOrderHelp -Dtestorder.help.format=json  # Gradle
```

The manifest is also at `docs/agent-manifest.json` in the repo.

**`requiresIndex`** — tasks that need the dependency index (`.test-order/test-dependencies.lz4`) fail with a `Run:` recovery hint if it's missing. See Error recovery below.

## Common tasks

| Goal | Maven | Gradle |
|---|---|---|
| Bootstrap / re-learn | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` |
| Affected tests only | `mvn test-order:affected test` | `./gradlew testOrderAffected test` |
| Affected + run deferred | `mvn test-order:affected test && mvn test-order:run-remaining test` | same pattern |
| Inspect ranking (JSON) | `mvn test-order:show -Dtestorder.show.format=json -q` | `./gradlew testOrderShow -Dtestorder.show.format=json --quiet` |
| Explain a test's rank | `mvn test-order:explain -Dtestorder.explain.class=com.example.FooTest` | `./gradlew testOrderExplain ...` |
| Tiered CI (fast → slow) | `mvn test-order:run-tiered` | `./gradlew testOrderRunTiered` |
| Multi-module order | `mvn test-order:reactor-order` | `./gradlew testOrderReactorOrder` |
| Interactive dashboard | `mvn test-order:dashboard` | `./gradlew testOrderDashboard` |
| Diagnose / health check | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` |
| Skip for one run | `mvn test -Dtestorder.skip=true` | `./gradlew test -Ptestorder.skip=true` |
| Export index + history | `mvn test-order:export-json` | `./gradlew testOrderExportJson` |

**Try without modifying POM (Maven):** `mvn me.bechberger:test-order-maven-plugin:LATEST:auto test`

**ML health:** `mvn test-order:show -Dtestorder.show.ml=true`

### Tiered CI pattern

Split one long suite into three phases — fast feedback first:

```bash
mvn test-order:tiered-select   # partition tests into tier1/2/3
mvn test-order:run-tier -Dtestorder.tier=1   # run tier 1 (fast, high-priority)
mvn test-order:run-tier -Dtestorder.tier=2   # run tier 2 in parallel / later
# or run all tiers in sequence:
mvn test-order:run-tiered
```

## JSON output

Tasks with `supportsJson: true` print parseable JSON to **stdout** when their `jsonProperty` is set. Use `-q` / `--quiet` to suppress build-log noise on stderr.

- `help` — `-Dtestorder.help.format=json` → the manifest
- `show`, `show-all` — `-Dtestorder.show.format=json` → class order, method order, ML health
- `export-json` — always JSON (full index + run history)
- `metrics` — always JSON, written to a file (APFD-style metrics)

Don't parse human-readable output from any other task.

## Error recovery: `Run:` lines

Recoverable errors append `Run: <command>` lines to the message:

```
Dependency index not found at /repo/.test-order/test-dependencies.lz4.
Run: mvn test -Dtestorder.mode=learn
Run: mvn test-order:diagnose
```

1. Grep for `^Run: ` lines.
2. Execute the **first** one; if it succeeds, retry the original command.
3. If the original still fails, run the **second** `Run:` line (usually `diagnose`).

Don't invent recovery commands — only run what the plugin provides.

## Stability tiers

Each manifest task has a `stability` field:

- `stable` — output shape and behaviour are committed.
- `experimental` — works, but surface may change (`optimize`, `coverage`, `detect-dependencies`, `analyze-mutations`).
- `deprecated` — stable replacement exists; the manifest description names it.

| Deprecated | Use instead |
|---|---|
| `show-order` | `show` |
| `show-method-order` | `show` |
| `analyze` | `show -Dtestorder.show.ml=true` |

Don't use deprecated goals in new invocations — suggest the replacement.

## Don't

- Don't parse human-readable output — use the JSON form.
- Don't run `mvn test-order:learn` + `mvn test` separately; `mvn test -Dtestorder.mode=learn` does both.
- Don't assume a goal exists — check the manifest.
- Don't suppress stderr; `Run:` recovery hints live there.
