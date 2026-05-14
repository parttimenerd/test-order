# CI Integration

test-order works out of the box in CI. The key requirement is **caching `.test-order/`** between runs so the dependency index and test history persist across builds.

For complete, ready-to-use workflow files see [ci-examples/](ci-examples/) (GitHub Actions, GitLab CI, Azure Pipelines).

## Caching

Cache `.test-order/` between CI steps to preserve the dependency index, test state, and hash snapshots. Without this cache, the first run on each PR falls back to learn mode (slower).

### What to cache

| Path | Purpose | Cache? |
|---|---|---|
| `.test-order/test-dependencies.lz4` | Dependency index | **Yes** |
| `.test-order/state.lz4` | Durations + failure history (improves scoring) | **Yes** |
| `.test-order/ml/history.lz4` | ML run history | Yes (if `ml.enabled=true`) |
| `.test-order/hashes/*.lz4` | Hash snapshots for `since-last-run` change detection | Yes |
| `**/target/test-order-deps/` | Per-module `.deps` files (Maven only) | Yes |
| `target/test-order-dashboard/` | Dashboard HTML (regenerated) | No |
| `target/test-order-selected.txt` | Transient selection list | No |

### GitHub Actions

#### Maven

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: |
      .test-order/
      **/target/test-order-deps/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/src/**/*.java') }}
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
    key: test-order-${{ runner.os }}-${{ hashFiles('**/src/**/*.java') }}
```

#### Gradle

```yaml
- name: Restore test-order index
  uses: actions/cache@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/*.java') }}
    restore-keys: test-order-${{ runner.os }}-

# ... run tests ...

- name: Save test-order data
  if: always()
  uses: actions/cache/save@v4
  with:
    path: .test-order/
    key: test-order-${{ runner.os }}-${{ hashFiles('**/*.java') }}
```

> Gradle doesn't use `target/test-order-deps/` — dependency data is written directly to `.test-order/`.

### GitLab CI

```yaml
cache:
  key: test-order-${CI_COMMIT_REF_SLUG}
  paths:
    - .test-order/
  policy: pull-push
```

### Azure Pipelines

```yaml
- task: Cache@2
  inputs:
    key: 'test-order | "$(Agent.OS)" | **/src/**/*.java'
    path: .test-order/
    restoreKeys: |
      test-order | "$(Agent.OS)"
```

## Key differences: Maven vs Gradle

| Concern | Maven | Gradle |
|---|---|---|
| Extra cache paths | `**/target/test-order-deps/` (per-module `.deps` files) | Not needed — written to `.test-order/` directly |
| Aggregation step | `mvn test-order:aggregate` (optional, merges `.deps`) | `./gradlew testOrderAggregate` |
| Multi-module index | Single shared `.test-order/` at root | Same — single `.test-order/` at root project |
| Cold start fallback | Learns on first run, or use `mvn test-order:download` | Learns on first run, or `./gradlew testOrderDownload` |

## Tips

- Use `restore-keys` (GitHub Actions) or a branch-based key (GitLab) so PRs inherit the cache from `main`.
- Always save the cache even when tests fail (`if: always()`) — failure history improves future scoring.
- For shallow clones (e.g. `fetch-depth: 1`), use `changeMode=since-last-run` instead of `since-last-commit`.
- Run `mvn test -Dtestorder.mode=learn` on your main branch periodically and commit `.test-order/test-dependencies.lz4` to keep the index fresh for feature branches.

## Complete workflow examples

See [ci-examples/](ci-examples/) for full pipeline configurations:

| File | Platform | Build Tool |
|------|----------|------------|
| [github-actions-tiered-maven.yml](ci-examples/github-actions-tiered-maven.yml) | GitHub Actions | Maven |
| [github-actions-tiered-gradle.yml](ci-examples/github-actions-tiered-gradle.yml) | GitHub Actions | Gradle |
| [gitlab-ci-tiered.yml](ci-examples/gitlab-ci-tiered.yml) | GitLab CI | Maven + Gradle |
| [azure-pipelines-tiered.yml](ci-examples/azure-pipelines-tiered.yml) | Azure Pipelines | Maven |
- Full CI workflow examples: [ci-examples/](ci-examples/).
