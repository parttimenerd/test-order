# AGENTS.md

This file is for LLM agents driving test-order on a user's project. For human-oriented docs see [README.md](README.md), [GETTING_STARTED.md](GETTING_STARTED.md), and [CLI_REFERENCE.md](CLI_REFERENCE.md).

> **Using Claude Code?** Install the bundled skill at [`skills/test-order/SKILL.md`](https://github.com/parttimenerd/test-order/blob/main/skills/test-order/SKILL.md) so Claude loads this guide automatically. See the [README](https://github.com/parttimenerd/test-order/blob/main/README.md#install-the-claude-code-skill) for one-line install instructions.

## What test-order does

Prioritises and selects tests based on a learned dependency index — for both Maven and Gradle. The first run learns; subsequent runs reorder tests so failures surface earlier and let you skip unaffected tests.

## How to invoke

- Maven: `mvn test-order:<goal>` (e.g. `mvn test-order:show`)
- Gradle: `./gradlew testOrder<Task>` (e.g. `./gradlew testOrderShow`)

Tasks are mirrored across both plugins where it makes sense. A handful are plugin-specific — see the manifest.

## Discover the available tasks

The full surface — every goal/task with its description, stability, JSON-output capability, and example invocation — is in [`docs/agent-manifest.json`](agent-manifest.json) and is also bundled into both plugins as a classpath resource.

Two ways to read it from a project that has the plugin applied:

```bash
# Maven (prints to stdout, parseable JSON)
mvn -B -ntp test-order:help -Dtestorder.help.format=json -q

# Gradle
./gradlew --quiet testOrderHelp -Dtestorder.help.format=json
```

The manifest top-level shape:

```json
{
  "schemaVersion": 1,
  "tool": "test-order",
  "conventions": { ... },
  "tasks": [ { "name": "show", "maven": "test-order:show", "gradle": "testOrderShow", "stability": "stable", "supportsJson": true, "jsonProperty": "testorder.show.format=json", "outputsTo": "stdout", "requiresIndex": true, ... }, ... ]
}
```

`schemaVersion` will only change for breaking format changes — additive fields don't bump it.

## JSON output

Tasks with `supportsJson: true` print parseable JSON to **stdout** when their `jsonProperty` is set. Use `-q` (Maven) or `--quiet` (Gradle) to silence lifecycle noise on stderr/log. Today these tasks support JSON:

- `help` — emits the manifest itself
- `show`, `show-all` — class order, method order, ML health (`-Dtestorder.show.format=json`)
- `export-json` — full dependency index + run history (always JSON)
- `metrics` — APFD-style metrics, written to a file (always JSON)

JSON outputs from `show*` and `export-json` already carry their own self-description (`meta.sectionsShown`, `exportVersion`, `depFormatVersion`).

Other tasks emit human-readable text. Don't try to parse it — invoke `export-json` or `show -Dtestorder.show.format=json` instead and read the JSON.

## Recovering from errors

Recoverable errors append a line of the form `Run: <command>`. Parse the message for `^Run: ` lines and execute them in order. Examples:

```
Dependency index not found at /repo/.test-order/test-dependencies.lz4.
Run: mvn test -Dtestorder.mode=learn
Run: mvn test-order:diagnose
```

When you see this, run the first `Run:` line. If it succeeds, retry the original command. If it still fails, run the second `Run:` line for diagnostics.

## Stability

Each task has a `stability` field in the manifest:

- `stable` — safe to call from an agent. Output shape and behaviour are committed.
- `experimental` — works, but the surface or output may change. OK to use; pin behaviour by reading the manifest.
- `deprecated` — still functions, but a stable replacement exists. The description names the replacement.

Today's deprecated tasks: `show-order`, `show-method-order`, `analyze`. Use `show` (with `-Dtestorder.show.ml=true` for ML health).

## What's *not* in the manifest

Lifecycle wiring (the way `prepare` binds to `process-test-classes`, or the way `testOrderAffected` configures the test task) is intentional plugin behaviour, not a goal you'd call standalone. The manifest lists tasks an agent might want to invoke; setup wiring is documented per-plugin in [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md) and the Gradle plugin's README.
