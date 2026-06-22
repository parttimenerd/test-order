---
name: test-order
description: Run, prioritise, and select tests with the test-order plugin (Maven and Gradle). Use when the user wants to run only affected tests, see which tests will run first, recover from "no dependency index" errors, or interpret test-order output. Covers help discovery via JSON manifest, learn vs order modes, and the Run:-line error-recovery convention.
---

# test-order skill

You are working in a project that uses [test-order](https://github.com/parttimenerd/test-order) — a plugin that reorders and selects tests based on a learned dependency index. This skill teaches you how to drive it.

## First contact: discover the surface

Before invoking anything, fetch the machine-readable task list. The plugin ships a JSON manifest with every goal, its stability, JSON-output capability, and example invocation:

```bash
# Maven
mvn -B -ntp -q test-order:help -Dtestorder.help.format=json

# Gradle
./gradlew --quiet testOrderHelp -Dtestorder.help.format=json
```

Both print parseable JSON to **stdout** (use `-q` / `--quiet` to suppress build-log noise on stderr). Top-level shape:

```json
{
  "schemaVersion": 1,
  "tool": "test-order",
  "tasks": [ { "name": "show", "maven": "test-order:show", "gradle": "testOrderShow", "stability": "stable", "supportsJson": true, "jsonProperty": "testorder.show.format=json", ... } ]
}
```

If the project has the plugin applied, prefer this over guessing goal names. The manifest is also documented at `docs/AGENTS.md` in the test-order repo.

## Most useful tasks

| Goal | Maven | Gradle | Use when |
|---|---|---|---|
| Run only affected tests | `mvn test-order:affected test` | `./gradlew testOrderAffected test` | Few files changed; want fast feedback |
| (Re)build the index | `mvn test -Dtestorder.mode=learn` | `./gradlew test -Dtestorder.mode=learn` | After big refactor; or as recovery from a missing index |
| Show what would run | `mvn test-order:show -Dtestorder.show.format=json -q` | `./gradlew testOrderShow -Dtestorder.show.format=json -q` | Inspect ordering / ML health without running tests |
| Full export of index + history | `mvn test-order:export-json` | `./gradlew testOrderExportJson` | Always-JSON dump for analysis |
| Diagnose | `mvn test-order:diagnose` | `./gradlew testOrderDiagnose` | When something's wrong and you need a status report |

## Tasks with stable JSON output

These tasks emit parseable JSON to stdout when the property is set:

- `help` — `-Dtestorder.help.format=json` (the manifest)
- `show`, `show-all` — `-Dtestorder.show.format=json` (class order, method order, ML health)
- `export-json` — always JSON (full index + run history)
- `metrics` — always JSON (written to a file, APFD-style metrics)

Anything else emits human-readable text — don't try to parse it. Call `export-json` or `show -Dtestorder.show.format=json` instead.

## Error recovery: parse `Run:` lines

When test-order can't proceed, its error message contains one or more lines starting with `Run: `. Each line is a literal command to execute. Example:

```
Dependency index not found at /repo/.test-order/test-dependencies.lz4.
Run: mvn test -Dtestorder.mode=learn
Run: mvn test-order:diagnose
```

Procedure:

1. Grep the failure output for `^Run: ` lines.
2. Execute the **first** one.
3. If it succeeds, retry the original command.
4. If the original still fails, run the **second** `Run:` line (usually `diagnose`) to gather context for the user.

Don't invent recovery commands — only run what the plugin tells you to run.

## Stability tiers

Each manifest task has a `stability` field:

- `stable` — safe to call. Output shape and behaviour are committed.
- `experimental` — works, but the surface may change. Pin behaviour by reading the manifest each session.
- `deprecated` — still functions but a stable replacement exists; the description names it. Currently deprecated: `show-order`, `show-method-order`, `analyze`. Use `show` (with `-Dtestorder.show.ml=true` for ML health).

## Invoking goals from the CLI

Maven users may not have the `test-order:` prefix configured. Two safe forms:

```bash
# Always works, no setup
mvn me.bechberger:test-order-maven-plugin:<goal>

# Works if the user has me.bechberger in ~/.m2/settings.xml <pluginGroups>
mvn test-order:<goal>
```

If a `mvn test-order:foo` invocation fails with `No plugin found for prefix 'test-order'`, fall back to the fully qualified form. The Gradle equivalent (`./gradlew testOrder<Foo>`) needs no such configuration.

## Don't

- Don't parse human-readable output of `show`/`show-all`/`dump` — use the JSON form.
- Don't `mvn test-order:learn` and `mvn test` separately when a single `mvn test -Dtestorder.mode=learn` does the same thing.
- Don't assume a goal exists — check the manifest first.
- Don't suppress test-order's stderr; the `Run:` recovery hints live there.
