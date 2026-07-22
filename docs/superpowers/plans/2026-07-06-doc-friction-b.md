# Documentation Friction Fixes (Approach B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce first-time-user friction in three docs by surfacing buried warnings, fixing a heading-level inconsistency, expanding "when to use" guidance for instrumentation modes, and adding two missing troubleshooting rows.

**Architecture:** Documentation-only changes touching three files. No code changes. Each task is a self-contained edit with grep verification and a commit.

**Tech Stack:** Markdown (`.md`, `.mdx`). No build required.

---

## File Map

| File | Change |
|---|---|
| `docs/CI.md` | Fix `#### Gradle` → `### Gradle` (line 118) so GitHub Actions Maven/Gradle are parallel `###` siblings matching the rest of the Caching section structure. |
| `docs/GETTING_STARTED.mdx` | (1) Strengthen the "Failed to load JUnit Platform" pitfalls-table row to name the proximate cause. (2) Add "when to use online vs offline" guidance to the Instrumentation modes callout. |
| `docs/CHEAT_SHEET.md` | Add two new troubleshooting rows: one for "tests still alphabetical despite learn run" and one for "binary read error / corrupt index". |

---

### Task 1: Fix heading level in `docs/CI.md`

**Files:**
- Modify: `docs/CI.md` line 118

Context: the `### GitHub Actions` section contains `#### Maven` and `#### Gradle` sub-headers. That nesting (`####` inside `###`) is correct for Maven, but the top-level `### Gradle` at line 36 under `## Minimum-viable setup` makes the document inconsistently use `###` for both a top-level platform section and a nested one. The audit flags line 118's `#### Gradle` — it should stay `####` (it is correctly nested under `### GitHub Actions`). The *actual* fix needed is the *document render*: the heading levels are structurally fine but the reader perceives them as mismatched when scanning the ToC. The simplest fix is to verify both GitHub Actions sub-sections use `####` (they already do at lines 93 and 118) and confirm the rest of the caching sub-sections use `###`. No change needed if they already match — verify first.

- [ ] **Step 1: Verify current heading levels in the Caching section**

Run:
```bash
grep -n "^###\|^####" /Users/i560383_1/code/experiments/test-order/docs/CI.md
```

Expected output (the key pattern — GitHub Actions subsections should be `####`, all other platforms `###`):
```
91:### GitHub Actions
93:#### Maven
118:#### Gradle
142:### GitLab CI
173:### Azure Pipelines
```

If the output matches this pattern, the heading structure is already correct and this task is complete — skip to commit with a no-op note.

If instead `#### Gradle` at line 118 shows as `### Gradle` (or any other inconsistency), apply the fix in Step 2.

- [ ] **Step 2: Apply fix only if Step 1 found a mismatch**

If `grep` showed `### Gradle` at line 118 (instead of `####`), fix it:

In `/Users/i560383_1/code/experiments/test-order/docs/CI.md`, find:

```markdown
### Gradle

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: test-order-${{ runner.os }}-
```

Replace `### Gradle` with `#### Gradle` (add one `#`).

- [ ] **Step 3: Verify headings are consistent**

Run:
```bash
grep -n "^###\|^####" /Users/i560383_1/code/experiments/test-order/docs/CI.md
```

Expected — GitHub Actions subsections are `####`, all other platforms are `###`:
```
91:### GitHub Actions
93:#### Maven
118:#### Gradle
142:### GitLab CI
173:### Azure Pipelines
```

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/CI.md && \
git commit -m "docs(ci): fix heading level for GitHub Actions Gradle subsection

#### Gradle (inside ### GitHub Actions) is the correct nesting.
Verified heading structure is now consistent across the Caching section."
```

If Step 1 confirmed no change was needed, skip the commit (nothing to commit).

---

### Task 2: Strengthen the "Failed to load JUnit Platform" pitfalls row in `docs/GETTING_STARTED.mdx`

**Files:**
- Modify: `docs/GETTING_STARTED.mdx` (Common Pitfalls table, the `"Failed to load JUnit Platform"` row)

The current row says the *cause* is "Snapshot repo added to project `repositories {}` instead of `pluginManagement.repositories {}`" but doesn't explain *why* that causes the error — users see "Failed to load JUnit Platform" and can't connect it to a repository placement mistake. Adding the mechanism ("stale snapshot JAR shadows your project's JUnit Platform version") makes it scannable.

- [ ] **Step 1: Locate the exact row text**

Run:
```bash
grep -n "Failed to load JUnit Platform" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
```

Expected: one match on a table row line.

- [ ] **Step 2: Replace the row**

In `/Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx`, find:

```markdown
| "Failed to load JUnit Platform" (Gradle) | Snapshot repo added to project `repositories {}` instead of `pluginManagement.repositories {}` | Move it to `pluginManagement.repositories {}` only — see Step 1 above |
```

Replace with:

```markdown
| "Failed to load JUnit Platform" (Gradle) | Snapshot repo in project `repositories {}` lets a stale snapshot JAR shadow your project's JUnit Platform version | Move the snapshot repo to `pluginManagement.repositories {}` only — see Step 1 above |
```

- [ ] **Step 3: Verify**

Run:
```bash
grep -c "stale snapshot JAR" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
```

Expected: `1`

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/GETTING_STARTED.mdx && \
git commit -m "docs(getting-started): clarify JUnit Platform failure cause in pitfalls table

Adds the mechanism (stale snapshot JAR shadows JUnit Platform) so the
symptom is immediately diagnosable without reading Step 1 in full."
```

---

### Task 3: Add "when to use online vs offline" guidance in `docs/GETTING_STARTED.mdx`

**Files:**
- Modify: `docs/GETTING_STARTED.mdx` (Instrumentation modes callout, after Step 2)

The current callout describes what online/offline modes are but doesn't say when to choose one over the other. Offline is right for 99% of users; online is only relevant when modifying bytecode on disk is prohibited. Adding one "when to use" sentence per mode makes the callout actionable.

- [ ] **Step 1: Locate the instrumentation modes callout**

Run:
```bash
grep -n "Instrumentation modes" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
```

Expected: one match.

- [ ] **Step 2: Replace the callout**

In `/Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx`, find:

```markdown
> **Instrumentation modes:** The default is `offline` (build-time bytecode instrumentation — the plugin modifies compiled classes before tests run, no `-javaagent` flag needed on the test JVM).
> To use online instrumentation instead (agent attached at runtime via `-javaagent`), pass `-Dtestorder.instrumentation=online`.
> Online mode avoids modifying bytecode on disk but requires the agent JAR to be locatable on the classpath.
> Both modes report dependency data through the same `IndexCollectorServer`.
```

Replace with:

```markdown
> **Instrumentation modes:** The default is `offline` (build-time bytecode instrumentation — the plugin modifies compiled classes before tests run, no `-javaagent` flag needed on the test JVM). **Use `offline` unless you have a specific reason not to** — it works with all standard Maven/Gradle setups.
> To use online instrumentation instead (agent attached at runtime via `-javaagent`), pass `-Dtestorder.instrumentation=online`.
> Online mode avoids modifying bytecode on disk; it is useful when your security policy or build environment prohibits class-file mutation (e.g., read-only build directories, OSGi containers, or strict reproducible-build pipelines). It requires the agent JAR to be locatable on the classpath.
> Both modes report dependency data through the same `IndexCollectorServer`.
```

- [ ] **Step 3: Verify**

Run:
```bash
grep -c "Use \`offline\` unless" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
grep -c "read-only build directories" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
```

Expected: `1` then `1`

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/GETTING_STARTED.mdx && \
git commit -m "docs(getting-started): add when-to-use guidance for instrumentation modes

Offline is the right default for almost all users. Online mode is
called out for the specific cases where it matters (read-only build
dirs, OSGi, reproducible-build pipelines)."
```

---

### Task 4: Add two missing rows to the Troubleshooting table in `docs/CHEAT_SHEET.md`

**Files:**
- Modify: `docs/CHEAT_SHEET.md` (Troubleshooting quick-fixes table)

Two scenarios are missing that users frequently hit:

1. **"Tests still run in alphabetical order despite learn run completing"** — the index was written but test-order isn't reordering. Distinct from "Wrote fallback payloads" (which means the index wasn't written at all). Common causes: `testorder.skip=true` set somewhere, or the JUnit extension isn't on the test classpath.

2. **"Binary read error / index appears corrupt"** — caused by a partial CI cache restore or an interrupted learn run leaving a truncated `.lz4` file. Fix is a clean learn.

- [ ] **Step 1: Locate the last row of the troubleshooting table**

Run:
```bash
grep -n "Every test lands in tier-1 in CI" /Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md
```

Expected: one match (the last data row before the `**Nuclear reset**` line).

- [ ] **Step 2: Add the two rows immediately after the last existing row**

In `/Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md`, find:

```markdown
| Every test lands in tier-1 in CI | Shallow clone hides `HEAD~1`. On GitLab set `variables: { GIT_DEPTH: 0 }`; on GitHub Actions use `actions/checkout@v4` with `fetch-depth: 0`; or use `-Dtestorder.changeMode=since-last-run`. |
```

Replace with:

```markdown
| Every test lands in tier-1 in CI | Shallow clone hides `HEAD~1`. On GitLab set `variables: { GIT_DEPTH: 0 }`; on GitHub Actions use `actions/checkout@v4` with `fetch-depth: 0`; or use `-Dtestorder.changeMode=since-last-run`. |
| Learn run completes but tests still run in alphabetical order | `testorder.skip=true` is set, or the JUnit/TestNG extension isn't on the test classpath | Check `-Dtestorder.debug=true` output; ensure no `-Dtestorder.skip=true` in CI env vars or `pom.xml`; verify `test-order-junit` or `test-order-testng` is on the test classpath |
| "Binary read error" or index appears corrupt | Partial CI cache restore or interrupted learn run left a truncated `.lz4` file | `rm -rf .test-order/ && mvn test` to force a clean learn run |
```

- [ ] **Step 3: Verify both rows landed**

Run:
```bash
grep -c "alphabetical order" /Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md
grep -c "Binary read error" /Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md
```

Expected: `1` then `1`

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/CHEAT_SHEET.md && \
git commit -m "docs(cheat-sheet): add two missing troubleshooting rows

- 'Tests still alphabetical despite learn run' — skip flag or missing
  extension on classpath; distinct from the 'Wrote fallback payloads'
  case where no index was written at all.
- 'Binary read error / corrupt index' — partial cache restore or
  interrupted learn; fix is rm -rf .test-order && mvn test."
```

---

### Task 5: Final verification

- [ ] **Step 1: Confirm all changes are present**

Run:
```bash
cd /Users/i560383_1/code/experiments/test-order && \
echo "=== CI.md heading structure ===" && \
grep -n "^###\|^####" docs/CI.md && \
echo "=== GETTING_STARTED stale snapshot JAR row ===" && \
grep -c "stale snapshot JAR" docs/GETTING_STARTED.mdx && \
echo "=== GETTING_STARTED offline guidance ===" && \
grep -c "Use \`offline\` unless" docs/GETTING_STARTED.mdx && \
echo "=== CHEAT_SHEET new rows ===" && \
grep -c "alphabetical order" docs/CHEAT_SHEET.md && \
grep -c "Binary read error" docs/CHEAT_SHEET.md
```

Expected:
- CI.md headings: `### GitHub Actions`, `#### Maven`, `#### Gradle`, `### GitLab CI`, `### Azure Pipelines` (in that order)
- `stale snapshot JAR`: `1`
- `Use \`offline\` unless`: `1`
- `alphabetical order`: `1`
- `Binary read error`: `1`

- [ ] **Step 2: View the final commit sequence**

Run:
```bash
cd /Users/i560383_1/code/experiments/test-order && git log --oneline -5
```

Expected: 3–4 commits from Tasks 1–4 on top of the GitLab CI friction commits from the previous session.

---

## Self-Review

**Spec coverage:**

| Design item | Task |
|---|---|
| Fix `#### Gradle` heading inconsistency in `CI.md` | Task 1 |
| Strengthen "Failed to load JUnit Platform" row with mechanism | Task 2 |
| Add "when to use online vs offline" to instrumentation callout | Task 3 |
| Add "tests still alphabetical despite learn run" troubleshooting row | Task 4 |
| Add "binary read error / corrupt index" troubleshooting row | Task 4 |

All five design items covered. No placeholders. All replacement text is complete and self-contained in each task.

**Placeholder scan:** No TBD, no "similar to above", no vague instructions. Every step shows the exact old string and exact new string.

**Type consistency:** No code — only Markdown. Symptom/fix wording is consistent with the surrounding table style (backtick-quoted commands, no trailing periods).
