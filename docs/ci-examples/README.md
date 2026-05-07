# CI Configuration Examples

Sample configurations for the three-tier test workflow:

| File | Platform | Build Tool |
|------|----------|------------|
| [github-actions-tiered-maven.yml](github-actions-tiered-maven.yml) | GitHub Actions | Maven |
| [github-actions-tiered-gradle.yml](github-actions-tiered-gradle.yml) | GitHub Actions | Gradle |
| [gitlab-ci-tiered.yml](gitlab-ci-tiered.yml) | GitLab CI | Maven + Gradle |
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

| Property | Default | Description |
|----------|---------|-------------|
| `testorder.changeMode` | `uncommitted` | How to detect changes (`uncommitted`, `auto`, `since-last-commit`, `since-last-run`, `explicit`) |
| `testorder.tiered.tier2Fraction` | `0.5` | Fraction of remaining test duration for tier 2 |
| `testorder.tiered.weightByDuration` | `true` | Select by duration budget (vs count) |
| `testorder.tiered.currentTier` | — | Required for `run-tier`: `2` or `3` |

## Tips

- **Cache `.test-order/`** between runs — it stores the dependency index, state, and hash snapshots needed for accurate change detection.
- **Use `fetch-depth: 0`** (GitHub Actions) or equivalent — `since-last-commit` mode needs git history.
- On **first run** without an index, tier 1 will fall through to running all tests.
- Set **`tier2Fraction=0.7`** for stricter coverage, **`0.3`** for faster feedback.
