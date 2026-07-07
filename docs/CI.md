# CI Integration

test-order works out of the box in CI. This page covers caching, cache-key strategy, index size management, and links to full pipeline examples.

## Minimum-viable setup (one job, 2 minutes to add)

The fastest way to get value in CI is to add caching around your existing test job. No pipeline restructuring needed.

> **Prerequisite:** Your `pom.xml` must declare the plugin with `<extensions>true</extensions>` — otherwise the index is written one build late (you'll see "Wrote fallback payloads" in the log). See the [Getting Started guide](GETTING_STARTED.mdx#step-1-add-the-plugin) for the full plugin snippet.

### Maven

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: test-order-${{ runner.os }}-

- name: Run tests
  run: mvn test

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
```

### Gradle

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: test-order-${{ runner.os }}-

- name: Run tests
  run: ./gradlew test

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
```

On the first run the plugin auto-learns (slight overhead). On subsequent runs, tests affecting your changes are prioritised automatically.

For three-tier pipelines (fastest feedback → broader → full) see [ci-examples/](https://github.com/parttimenerd/test-order/tree/main/docs/ci-examples).

---

## Caching

Cache `.test-order/` between CI runs so PRs benefit from the existing index.

### What to cache

| Path | Purpose | Cache? |
|---|---|---|
| `.test-order/test-dependencies.lz4` | Dependency index | **Yes** |
| `.test-order/state.lz4` | Durations + failure history (improves scoring) | **Yes** |
| `.test-order/ml/history.lz4` | ML run history | Yes (if `ml.enabled=true`) |
| `.test-order/hashes*.lz4` (single-module) or `.test-order/hashes/` (multi-module) | Hash snapshots for `since-last-run` change detection | Yes |
| `**/target/test-order-deps/` | Per-module `.deps` files (Maven only) | Yes |
| `target/test-order-dashboard/` | Dashboard HTML (regenerated) | No |
| `target/test-order-selected.txt` | Transient selection list | No |

### Choosing a cache key

The cache key controls which prior run a job can inherit from.

| Scenario | Recommended key |
|---|---|
| PRs always inherit from `main` | `test-order-${{ runner.os }}-${{ github.base_ref \|\| github.ref_name }}` |
| Separate cache per branch | `test-order-${{ runner.os }}-${{ github.ref_name }}` |
| Single shared cache | `test-order-${{ runner.os }}` |

> **Avoid** keying on `hashFiles('**/src/**/*.java')`. Every source change busts the cache, defeating the purpose. Use a branch-name or target-branch key instead, with `restore-keys` as a fallback so PRs always inherit from their base branch.

### GitHub Actions

#### Maven

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: |
      test-order-${{ runner.os }}-

# ... run tests ...

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
```

#### Gradle

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
    restore-keys: test-order-${{ runner.os }}-

# ... run tests ...

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}
```

> Gradle doesn't use `target/test-order-deps/` — dependency data is written directly to `.test-order/`.

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

### Azure Pipelines

```yaml
- task: Cache@2
  inputs:
    key: 'test-order | "$(Agent.OS)" | $(Build.SourceBranchName)'
    path: .test-order/
    restoreKeys: |
      test-order | "$(Agent.OS)"
```

---

## Index size management

The dependency index (`.test-order/test-dependencies.lz4`) grows with the number of test classes and the granularity of dependency tracking.

### Tracking granularity

| Mode | What is recorded | Index size | Overhead | Accuracy |
|------|-----------------|-----------|----------|----------|
| `MEMBER` *(default)* | Which fields/methods of each class each test accesses | Larger | ~13% | Highest |
| `CLASS` | Method/constructor entry + foreign static-field access per class | Smaller | ~13% | Good for most projects |

Switch to `CLASS` mode if the index grows beyond ~50 MB or learn runs are slow:

```xml
<!-- pom.xml plugin configuration -->
<configuration>
  <instrumentationMode>CLASS</instrumentationMode>
</configuration>
```

Or pass it on the command line:

```bash
mvn test -Dtestorder.mode=learn -Dtestorder.instrumentation.mode=CLASS
```

### Committing vs caching the index

| Approach | Best for |
|---|---|
| Commit `.test-order/test-dependencies.lz4` | Shared monorepos, air-gapped CI, or teams that want instant cold-starts without CI setup |
| Cache (CI cache) | Standard CI setups — easiest to maintain |
| Download from previous CI run (`mvn test-order:download`) | Projects that already upload artifacts; see [test-order-ci/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-ci/README.md) |

If you commit the index, add machine-local files to `.gitignore`:

```gitignore
.test-order/hashes/
.test-order/state.lz4
```

### Retention and freshness

- Indexes accumulate data from every learn run. They don't need periodic cleanup — deleted test classes are scored with zero weight and naturally fall to the bottom.
- Run `mvn test -Dtestorder.mode=learn` (or `./gradlew test -Dtestorder.mode=learn`) on `main` whenever your test suite changes significantly (new modules, major refactors).
- In CI, a scheduled weekly learn job on `main` keeps the index fresh without any manual intervention. See the [mutation-testing example](../.github/workflows/mutation-testing.yml) for a template.

---

## Key differences: Maven vs Gradle

| Concern | Maven | Gradle |
|---|---|---|
| Extra cache paths | `**/target/test-order-deps/` (single-module only; multi-module writes deps to `.test-order/deps/` already covered above) | Not needed — written to `.test-order/` directly |
| Aggregation step | `mvn test-order:aggregate` (optional, merges `.deps`) | `./gradlew testOrderAggregate` |
| Multi-module index | Single shared `.test-order/` at root | Same — single `.test-order/` at root project |
| Cold start fallback | Learns on first run, or use `mvn test-order:download` | Learns on first run, or `./gradlew testOrderDownload` |

---

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

---

## Complete workflow examples

See [ci-examples/](https://github.com/parttimenerd/test-order/tree/main/docs/ci-examples) for full pipeline configurations:

| File | Platform | Build Tool |
|------|----------|------------|
| [github-actions-tiered-maven.yml](https://github.com/parttimenerd/test-order/blob/main/docs/ci-examples/github-actions-tiered-maven.yml) | GitHub Actions | Maven |
| [github-actions-tiered-gradle.yml](https://github.com/parttimenerd/test-order/blob/main/docs/ci-examples/github-actions-tiered-gradle.yml) | GitHub Actions | Gradle |
| [gitlab-ci-tiered.yml](https://github.com/parttimenerd/test-order/blob/main/docs/ci-examples/gitlab-ci-tiered.yml) | GitLab CI | Maven |
| [azure-pipelines-tiered.yml](https://github.com/parttimenerd/test-order/blob/main/docs/ci-examples/azure-pipelines-tiered.yml) | Azure Pipelines | Maven |
