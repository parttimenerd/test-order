# CI Friction Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update the four CI documentation files to reflect confirmed findings from live GitLab CE pipeline testing against `parttimenerd/sample-ci-test-order`, so first-time GitLab users get a working three-tier pipeline without debugging.

**Architecture:** Documentation-only change touching four files. The verified GitLab pipeline replaces the current sample entirely; `docs/CI.md` gains a GitLab-specific block explaining `GIT_DEPTH`, `fallback_keys`, and the correct Maven image; `GETTING_STARTED.mdx` and `CHEAT_SHEET.md` each gain one row in existing troubleshooting tables so the "all tests land in tier-1" symptom is discoverable.

**Tech Stack:** Markdown (`.md`, `.mdx`), YAML (GitLab CI). No code changes.

---

## File Map

| File | Change |
|---|---|
| `docs/ci-examples/gitlab-ci-tiered.yml` | Full rewrite: replace with verified pipeline (learn stage + three tiers, correct image, `GIT_DEPTH: 0`, `fallback_keys`). |
| `docs/CI.md` | Replace GitLab CI caching example with block including `fallback_keys` and `GIT_DEPTH: 0`; add a GitLab-specific note explaining the three findings; extend the Tips section with a "shallow-clone impact" table. |
| `docs/GETTING_STARTED.mdx` | Add one row to the Common Pitfalls table for the shallow-clone / `GIT_DEPTH` symptom. |
| `docs/CHEAT_SHEET.md` | Add one row to the Troubleshooting quick-fixes table for the same symptom. |

All findings included below were confirmed against a live GitLab CE instance and `parttimenerd/sample-ci-test-order`. No untested content appears in this plan.

---

### Task 1: Replace `docs/ci-examples/gitlab-ci-tiered.yml` with the verified pipeline

**Files:**
- Modify: `docs/ci-examples/gitlab-ci-tiered.yml` (full rewrite)

- [ ] **Step 1: Overwrite the file with the verified content**

Replace the entire contents of `/Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml` with:

```yaml
# GitLab CI — test-order integration
#
# On the default branch: full learn run (builds the dependency index).
# On all other branches/MRs: three-tier fail-fast pipeline.
#
# GIT_DEPTH: 0  — full history so since-last-commit can compare HEAD~1.
#   Without this (default depth <= 50), HEAD~1 may be unavailable.
#   test-order then warns "treating all tracked source files as changed"
#   and dumps all tests into tier-1 — tiering is defeated.
# fallback_keys lets feature branches inherit the main-branch index.

stages:
  - learn
  - tier-1
  - tier-2
  - tier-3

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  GIT_DEPTH: 0
  GIT_STRATEGY: clone

.cache: &cache
  cache:
    - key: test-order-${CI_COMMIT_REF_SLUG}
      fallback_keys:
        - test-order-${CI_DEFAULT_BRANCH}
      paths:
        - .test-order/
        - .m2/repository/

# ── Learn (default branch only) ──────────────────────────────────────────────

learn:
  stage: learn
  image: maven:3.9-eclipse-temurin-17
  <<: *cache
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  script:
    - mvn -B -ntp test -Dtestorder.mode=learn

# ── Three-tier (non-default branches / MRs) ──────────────────────────────────

tier-1:
  stage: tier-1
  image: maven:3.9-eclipse-temurin-17
  <<: *cache
  rules:
    - if: $CI_COMMIT_BRANCH != $CI_DEFAULT_BRANCH
  script:
    - mvn -B -ntp test-order:tiered-select test
        -Dtestorder.changeMode=since-last-commit
        -Dtestorder.tiered.tier2Fraction=0.5
        -Dsurefire.failIfNoSpecifiedTests=false
  artifacts:
    paths:
      - target/test-order-tier*.txt
    expire_in: 1 hour

tier-2:
  stage: tier-2
  needs: [tier-1]
  image: maven:3.9-eclipse-temurin-17
  <<: *cache
  rules:
    - if: $CI_COMMIT_BRANCH != $CI_DEFAULT_BRANCH
  script:
    - mvn -B -ntp test-order:run-tier test
        -Dtestorder.tiered.currentTier=2
        -Dsurefire.failIfNoSpecifiedTests=false

tier-3:
  stage: tier-3
  needs: [tier-2]
  image: maven:3.9-eclipse-temurin-17
  <<: *cache
  rules:
    - if: $CI_COMMIT_BRANCH != $CI_DEFAULT_BRANCH
  script:
    - mvn -B -ntp test-order:run-tier test
        -Dtestorder.tiered.currentTier=3
        -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 2: Verify the file parses as valid YAML**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('/Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml'))" && echo OK`
Expected: `OK`

- [ ] **Step 3: Sanity-check the four confirmed facts appear in the file**

Run:
```bash
grep -c "GIT_DEPTH: 0" /Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml
grep -c "fallback_keys" /Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml
grep -c "maven:3.9-eclipse-temurin-17" /Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml
grep -c "^learn:" /Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml
```
Expected output (each on its own line):
```
1
1
4
1
```

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/ci-examples/gitlab-ci-tiered.yml && \
git commit -m "docs(ci-examples): replace GitLab pipeline with verified three-tier config

Tested against parttimenerd/sample-ci-test-order on local GitLab CE.
Adds a learn stage on the default branch, GIT_DEPTH: 0 (so HEAD~1 is
reachable for since-last-commit), fallback_keys for feature branches
to inherit the main-branch index, and switches to
maven:3.9-eclipse-temurin-17 (eclipse-temurin:17 has no mvn)."
```

---

### Task 2: Update the GitLab CI section of `docs/CI.md`

**Files:**
- Modify: `docs/CI.md` (GitLab CI subsection at lines ~140-149; Tips section at lines ~224-229)

- [ ] **Step 1: Replace the GitLab CI caching example**

In `/Users/i560383_1/code/experiments/test-order/docs/CI.md`, find the block:

```markdown
### GitLab CI

```yaml
cache:
  # Branch-coupled key: PRs inherit from their source branch; main builds write a fresh one.
  key: test-order-${CI_COMMIT_REF_SLUG}
  paths:
    - .test-order/
  policy: pull-push
```
```

Replace it with:

````markdown
### GitLab CI

```yaml
variables:
  # Full history — since-last-commit needs HEAD~1 to be reachable.
  GIT_DEPTH: 0

cache:
  # Feature branches read from their own key first, then fall back to
  # the index built on the default branch. Without fallback_keys,
  # feature-branch jobs run without an index and fail with
  # "No dependency index".
  - key: test-order-${CI_COMMIT_REF_SLUG}
    fallback_keys:
      - test-order-${CI_DEFAULT_BRANCH}
    paths:
      - .test-order/
      - .m2/repository/
```

> **Three GitLab-specific pitfalls (all confirmed on GitLab CE):**
>
> 1. **`GIT_DEPTH: 0` is required for `since-last-commit`.** GitLab's default
>    shallow clone means `HEAD~1` is often unavailable. test-order then logs
>    `git revision HEAD~1 is unavailable; treating all tracked source files as
>    changed` and every test lands in tier-1 — tiering is silently defeated.
>    Either set `GIT_DEPTH: 0` (recommended) or switch to
>    `-Dtestorder.changeMode=since-last-run`.
> 2. **`fallback_keys` is not optional for feature-branch pipelines.** The
>    per-branch cache key means a fresh feature branch has no cache of its
>    own; without `fallback_keys: [test-order-${CI_DEFAULT_BRANCH}]` the tier-1
>    job errors out with "No dependency index".
> 3. **Use `maven:3.9-eclipse-temurin-17`, not `eclipse-temurin:17`.** The
>    plain temurin image has no `mvn` on `PATH`; jobs fail with
>    `mvn: command not found`. The `maven:3.9-eclipse-temurin-17` image
>    bundles both Java 17 and Maven 3.9.
>
> Avoid `CI_RUNNER_EXECUTABLE_ARCH` inside cache keys — it expands to
> `linux/amd64` and the slash is normalised by GitLab, which is confusing
> without any real benefit for a single-arch runner pool.
````

- [ ] **Step 2: Extend the Tips section with a shallow-clone impact table**

In `/Users/i560383_1/code/experiments/test-order/docs/CI.md`, find the Tips list:

```markdown
## Tips

- Always save the cache even when tests fail (`if: always()`) — failure history improves future scoring.
- For shallow clones (e.g. `fetch-depth: 1`), use `changeMode=since-last-run` instead of `since-last-commit`.
- With multiple concurrent PR builds writing to the same cache key, the last writer wins — this is safe, as all runners produce equivalent indexes from the same source.
- Avoid caching hash snapshots (`*.lz4` in `.test-order/` or `.test-order/hashes/`) across heterogeneous runner images (different OS/JDK versions) when using `since-last-run` change detection — hash snapshots are machine-local and may produce spurious diffs.
```

Replace it with:

```markdown
## Tips

- Always save the cache even when tests fail (`if: always()`) — failure history improves future scoring.
- For shallow clones (e.g. `fetch-depth: 1` on GitHub Actions, default `GIT_DEPTH` on GitLab), either deepen the clone or use `changeMode=since-last-run` instead of `since-last-commit`.
- With multiple concurrent PR builds writing to the same cache key, the last writer wins — this is safe, as all runners produce equivalent indexes from the same source.
- Avoid caching hash snapshots (`*.lz4` in `.test-order/` or `.test-order/hashes/`) across heterogeneous runner images (different OS/JDK versions) when using `since-last-run` change detection — hash snapshots are machine-local and may produce spurious diffs.

### Shallow-clone impact on `since-last-commit`

`since-last-commit` compares `HEAD` to `HEAD~1`. On a shallow clone `HEAD~1` may not exist. When that happens, test-order falls back to treating every tracked source file as changed, which pushes every test into tier-1 and defeats tiering.

| Platform | Default clone depth | Fix for `since-last-commit` |
|---|---|---|
| GitLab CI | ~50 (shallow) | Set `variables: { GIT_DEPTH: 0 }` |
| GitHub Actions | 1 (shallow) | Use `actions/checkout@v4` with `fetch-depth: 0` |
| Jenkins | full by default | No change needed |

If you can't (or don't want to) deepen the clone, use `-Dtestorder.changeMode=since-last-run` instead — it uses cached source hashes rather than git history.
```

- [ ] **Step 3: Verify markdown edits landed**

Run:
```bash
grep -c "### GitLab CI" /Users/i560383_1/code/experiments/test-order/docs/CI.md
grep -c "Shallow-clone impact" /Users/i560383_1/code/experiments/test-order/docs/CI.md
grep -c "fallback_keys" /Users/i560383_1/code/experiments/test-order/docs/CI.md
grep -c "maven:3.9-eclipse-temurin-17" /Users/i560383_1/code/experiments/test-order/docs/CI.md
```

Expected output:
```
1
1
2
1
```

- [ ] **Step 4: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/CI.md && \
git commit -m "docs(ci): document GitLab pitfalls (GIT_DEPTH, fallback_keys, image)

Adds the three GitLab-specific findings from local GitLab CE testing:
- GIT_DEPTH: 0 is required for since-last-commit; default shallow
  clone defeats tiering silently.
- fallback_keys is required for feature branches to inherit the
  main-branch index.
- maven:3.9-eclipse-temurin-17 is the correct image
  (eclipse-temurin:17 has no mvn).

Also adds a shallow-clone impact table to the Tips section."
```

---

### Task 3: Add a shallow-clone row to `docs/GETTING_STARTED.mdx`

**Files:**
- Modify: `docs/GETTING_STARTED.mdx` (Common Pitfalls table, line ~339)

- [ ] **Step 1: Add one new row to the Common Pitfalls table**

In `/Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx`, find the last row of the Common Pitfalls table:

```markdown
| "Failed to load JUnit Platform" (Gradle) | Snapshot repo added to project `repositories {}` instead of `pluginManagement.repositories {}` | Move it to `pluginManagement.repositories {}` only — see Step 1 above |
```

Add a new row immediately after it (so it becomes the new last row of the table):

```markdown
| Every test lands in tier-1 in CI (tiering defeated) | Shallow clone: `HEAD~1` unavailable, so `since-last-commit` treats every source file as changed. Look for the log line `git revision HEAD~1 is unavailable`. | On GitLab: set `variables: { GIT_DEPTH: 0 }`. On GitHub Actions: `actions/checkout@v4` with `fetch-depth: 0`. Or switch to `-Dtestorder.changeMode=since-last-run`. |
```

- [ ] **Step 2: Verify the row was added exactly once**

Run:
```bash
grep -c "Every test lands in tier-1" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
grep -c "GIT_DEPTH: 0" /Users/i560383_1/code/experiments/test-order/docs/GETTING_STARTED.mdx
```

Expected output:
```
1
1
```

- [ ] **Step 3: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/GETTING_STARTED.mdx && \
git commit -m "docs(getting-started): add shallow-clone pitfall row

Documents the confirmed GitLab CI failure mode where the default
GIT_DEPTH makes HEAD~1 unreachable, pushing every test into tier-1."
```

---

### Task 4: Add a shallow-clone row to `docs/CHEAT_SHEET.md`

**Files:**
- Modify: `docs/CHEAT_SHEET.md` (Troubleshooting quick-fixes table, line ~165)

- [ ] **Step 1: Add one new row to the Troubleshooting quick-fixes table**

In `/Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md`, find the last row of the Troubleshooting quick-fixes table:

```markdown
| Tests skipped unexpectedly | Cold-start without index — `affected`/tiered goals fall back to all tests; run `mvn test` first |
```

Add a new row immediately after it (so it becomes the new last row of the table):

```markdown
| Every test lands in tier-1 in CI | Shallow clone hides `HEAD~1`. On GitLab set `variables: { GIT_DEPTH: 0 }`; on GitHub Actions use `actions/checkout@v4` with `fetch-depth: 0`; or use `-Dtestorder.changeMode=since-last-run`. |
```

- [ ] **Step 2: Verify the row was added exactly once**

Run: `grep -c "Every test lands in tier-1 in CI" /Users/i560383_1/code/experiments/test-order/docs/CHEAT_SHEET.md`
Expected: `1`

- [ ] **Step 3: Commit**

```bash
cd /Users/i560383_1/code/experiments/test-order && \
git add docs/CHEAT_SHEET.md && \
git commit -m "docs(cheat-sheet): add shallow-clone troubleshooting row"
```

---

### Task 5: Final cross-file consistency check

**Files:** none (verification only)

- [ ] **Step 1: Confirm all four files carry the fixes**

Run:
```bash
cd /Users/i560383_1/code/experiments/test-order && \
echo "=== GIT_DEPTH mentions ===" && \
grep -l "GIT_DEPTH" docs/ci-examples/gitlab-ci-tiered.yml docs/CI.md docs/GETTING_STARTED.mdx docs/CHEAT_SHEET.md && \
echo "=== fallback_keys mentions ===" && \
grep -l "fallback_keys" docs/ci-examples/gitlab-ci-tiered.yml docs/CI.md && \
echo "=== maven:3.9-eclipse-temurin-17 mentions ===" && \
grep -l "maven:3.9-eclipse-temurin-17" docs/ci-examples/gitlab-ci-tiered.yml docs/CI.md
```

Expected:
- `GIT_DEPTH` appears in all four files.
- `fallback_keys` appears in the yaml example and `CI.md`.
- `maven:3.9-eclipse-temurin-17` appears in the yaml example and `CI.md`.

- [ ] **Step 2: Confirm the stale bare temurin image is gone from the GitLab example**

Run: `grep -n "eclipse-temurin:17" /Users/i560383_1/code/experiments/test-order/docs/ci-examples/gitlab-ci-tiered.yml`
Expected: no matches (exit code 1).

Note: the string `eclipse-temurin-17` (with a hyphen, inside `maven:3.9-eclipse-temurin-17`) is expected and correct. Only the bare `eclipse-temurin:17` (with a colon) is stale.

- [ ] **Step 3: View the final commit sequence**

Run: `cd /Users/i560383_1/code/experiments/test-order && git log --oneline -4`
Expected: four commits from Tasks 1-4, newest first (`docs(cheat-sheet)`, `docs(getting-started)`, `docs(ci)`, `docs(ci-examples)`).

---

## Self-Review

**Spec coverage:** All seven confirmed facts from the ask are covered.

| Confirmed fact | Where it lands |
|---|---|
| 1. `GIT_DEPTH` matters | yaml (variables + header comment); `CI.md` cache block + pitfall note + Tips table; `GETTING_STARTED.mdx` row; `CHEAT_SHEET.md` row |
| 2. `fallback_keys` required | yaml (cache anchor); `CI.md` cache block + pitfall #2 |
| 3. `CI_RUNNER_EXECUTABLE_ARCH` has a slash | `CI.md` note under the GitLab pitfalls block |
| 4. Correct Maven image | yaml (every job); `CI.md` pitfall #3 |
| 5. Verified full pipeline structure | yaml (Task 1) |
| 6. `extensions=true` required | Already covered in existing `CI.md` prerequisite (line 9) and in `GETTING_STARTED.mdx` / `CHEAT_SHEET.md` "Wrote fallback payloads" rows — untouched in this plan on purpose |
| 7. `<argLine>@{argLine}</argLine>` pattern | Already covered in existing `GETTING_STARTED.mdx` and `CHEAT_SHEET.md` JaCoCo rows — untouched on purpose |

**Placeholder scan:** No "TBD", "similar to above", "add appropriate ...", or "handle edge cases". Every step contains its full replacement text. Every command has a concrete file path and expected output.

**Type consistency:** Cache key name (`test-order-${CI_COMMIT_REF_SLUG}` with `fallback_keys: [test-order-${CI_DEFAULT_BRANCH}]`), image name (`maven:3.9-eclipse-temurin-17`), and property name (`GIT_DEPTH: 0`) are identical across every task and every file.

**Untested content:** Azure Pipelines is not touched. GitHub Actions guidance is limited to the shallow-clone fix (which is standard `actions/checkout` behavior, not test-order-specific). Nexus / restricted artifact repos, runner heterogeneity, and `pluginGroups` are not mentioned.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-06-ci-friction-fixes.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task with review between tasks.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`.

Which approach?
