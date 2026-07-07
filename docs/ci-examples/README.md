# CI Configuration Examples

Sample configurations for the three-tier test workflow:

| File | Platform | Build Tool |
|------|----------|------------|
| [github-actions-tiered-maven.yml](github-actions-tiered-maven.yml) | GitHub Actions | Maven |
| [github-actions-tiered-gradle.yml](github-actions-tiered-gradle.yml) | GitHub Actions | Gradle |
| [gitlab-ci-tiered.yml](gitlab-ci-tiered.yml) | GitLab CI | Maven |
| [azure-pipelines-tiered.yml](azure-pipelines-tiered.yml) | Azure Pipelines | Maven |

## How it works

```
┌─────────────────────────┐
│  Tier 1 (fastest)       │  Change-affected + @AlwaysRun + new tests
│  ~10-20% of suite       │  Fails fast on regressions in changed code
└───────────┬─────────────┘
            │ pass
┌───────────▼─────────────┐
│  Tier 2 (medium)        │  Top-scored 50% of remaining (by duration budget)
│  ~40-50% of suite       │  Catches broader regressions quickly
└───────────┬─────────────┘
            │ pass
┌───────────▼─────────────┐
│  Tier 3 (full)          │  Everything else
│  remaining tests        │  Ensures full coverage
└─────────────────────────┘
```

## Key properties

A complete list is in [CLI_REFERENCE.mdx](../CLI_REFERENCE.mdx). The most commonly tuned properties for CI:

| Property | Default | Description |
|----------|---------|-------------|
| `testorder.tiered.tier2Fraction` | `0.5` | Fraction of remaining test duration for tier 2 |
| `testorder.tiered.weightByDuration` | `true` | Select tier 2 by duration budget (vs count) |
| `testorder.tiered.currentTier` | — | Required for `run-tier`: `2` or `3` |
| `testorder.tiered.shard` | — | Shard tier 3 across parallel runners: `1/3`, `2/3`, `3/3` |
| `testorder.changeMode` | `uncommitted` | `uncommitted`, `auto`, `since-last-commit`, `since-last-run`, `explicit` |
| `testorder.ci.summary` | `false` | Write `target/test-order-summary.md` and `target/test-order-summary.json` after each run |
| `testorder.ci.githubStepSummary` | `false` | Append summary to `$GITHUB_STEP_SUMMARY` (GitHub Actions) |
| `testorder.ci.prComment` | `false` | Post summary as PR comment via `GITHUB_TOKEN` |

## Tips

- **Cache `.test-order/`** between runs — it stores the dependency index, state, and hash snapshots needed for accurate change detection.
- **Use `fetch-depth: 0`** (GitHub Actions) or equivalent — `since-last-commit` mode needs git history.
- **Use a branch-coupled cache key** (`test-order-${{ runner.os }}-${{ github.base_ref || github.ref_name }}`) so PRs always inherit from their base branch without busting the cache on every commit.
- On **first run** without an index, tier 1 will fall through to running all tests.
- Set **`tier2Fraction=0.7`** for stricter coverage, **`0.3`** for faster feedback.
- **Always save the cache when tests fail** (`if: always()`) — failure history improves future scoring.
