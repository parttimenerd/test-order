# test-order-ci

Downloads pre-built dependency indexes from CI systems so that feature branches
skip the cold-start learn phase. Integrated into the Maven and Gradle plugins —
when a `.test-order/download-config.yml` exists (or the current environment is
auto-detectable), **auto mode** downloads the index automatically before falling
back to learn mode.

## Supported providers

| Provider | Config key | Auth |
|----------|-----------|------|
| GitHub Actions | `ci.github` | `GITHUB_TOKEN` env var |
| GitLab CI | `ci.gitlab` | Token env var (configurable) |
| Maven / Nexus / Artifactory | `ci.maven` | Bearer / Basic / none |
| Generic HTTP | `ci.http` | Bearer / Basic / none |

## Auto-detect (no config file required)

When running on GitHub Actions or GitLab CI, test-order can detect the provider
automatically from environment variables — no `download-config.yml` needed:

| Environment | Required env vars | What is inferred |
|---|---|---|
| GitHub Actions | `GITHUB_ACTIONS=true`, `GITHUB_REPOSITORY`, `GITHUB_WORKFLOW` or `GITHUB_WORKFLOW_REF` | owner, repo, workflow file, artifact name `test-order-deps` |
| GitLab CI | `GITLAB_CI=true`, `CI_PROJECT_PATH`, `CI_JOB_NAME` | project path, job name |

Auto-detection is a best-effort fallback. Create a `download-config.yml` to
override any inferred value (e.g. a custom artifact name or a different branch).

## Configuration

Place `.test-order/download-config.yml` in your project root (pick **one** provider):

### GitHub Actions

```yaml
ci:
  github:
    owner: your-org
    repo: your-project
    workflow: ci.yml
    artifact-name: test-order-deps
    branch: main
```

### GitLab CI

```yaml
ci:
  gitlab:
    base-url: https://gitlab.com        # optional, default https://gitlab.com
    project-id: "my-group/my-project"   # path or numeric ID
    job-name: build
    artifact-name: test-dependencies.lz4
    branch: main
    token-env: GITLAB_TOKEN
```

### Maven / Nexus / Artifactory

```yaml
ci:
  maven:
    url: https://repo.example.com/repository/snapshots
    group-id: com.example
    artifact-id: my-service
    version: LATEST          # LATEST, RELEASE, or a fixed version
    classifier: test-deps    # optional; default: test-deps
    extension: lz4           # optional; default: lz4
    auth: bearer             # bearer, basic, or none; default: none
    token-env: NEXUS_TOKEN   # optional
```

When `version=LATEST` or `version=RELEASE`, the downloader fetches
`maven-metadata.xml` to resolve the latest published version automatically.

### Generic HTTP

```yaml
ci:
  http:
    url: https://ci.example.com/artifacts/test-dependencies.lz4
    auth: bearer          # bearer, basic, or none
    token-env: CI_TOKEN
```

### Proxy support (optional)

```yaml
proxy:
  host: proxy.corp.com
  port: 8080
  type: http              # http or socks5
```

## Optional: download state alongside the index

The `state.lz4` file contains test duration history and failure counts, which
improve scoring quality. You can download it alongside the dependency index:

```java
// Maven / Gradle plugin integration
CiDepDownloadManager.downloadIfConfigured(projectDir, indexTarget, stateTarget);
```

For GitHub Actions and GitLab CI, test-order looks for a companion artifact
named `<artifact-name>-state` (GitHub) or `test-state.lz4` (GitLab). Upload it
alongside the dep index in your CI pipeline:

```yaml
# GitHub Actions — upload both artifacts from main
- name: Upload dependency index
  uses: actions/upload-artifact@v4
  with:
    name: test-order-deps
    path: .test-order/test-dependencies.lz4

- name: Upload state
  uses: actions/upload-artifact@v4
  with:
    name: test-order-deps-state
    path: .test-order/state.lz4
```

## Usage

### Maven

```bash
# Standalone goal — download the index from CI
mvn test-order:download

# Auto mode — downloads automatically when config is present (or env is detectable)
mvn test -Dtestorder.mode=auto
```

### Gradle

```bash
# Standalone task
./gradlew testOrderDownload

# Auto mode — downloads automatically when config is present (or env is detectable)
./gradlew test
```

## Full example: GitHub Actions workflow for a Maven project

Imagine a Maven project `acme/shop-service`. On the `main` branch CI runs tests
in **learn mode** and uploads the dependency index. On pull-request branches the
index is downloaded from CI so tests run in **order mode** immediately.

### `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  actions: read               # required for the artifact download API

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: maven

      # ── main branch: learn ──────────────────────────────────
      - name: Run tests (learn mode)
        if: github.ref == 'refs/heads/main'
        run: mvn verify -Dtestorder.mode=learn

      - name: Aggregate dependency index
        if: github.ref == 'refs/heads/main'
        run: mvn test-order:aggregate

      - name: Upload dependency index
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v4
        with:
          name: test-order-deps
          path: .test-order/test-dependencies.lz4
          retention-days: 30

      - name: Upload state
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v4
        with:
          name: test-order-deps-state
          path: .test-order/state.lz4
          retention-days: 30

      # ── PR branches: download index, then order ─────────────
      # No download-config.yml needed — auto-detected from GITHUB_ACTIONS env vars.
      - name: Run tests (auto — downloads index from main)
        if: github.ref != 'refs/heads/main'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: mvn verify -Dtestorder.mode=auto
```

With this setup every PR gets fast, priority-ordered tests without having to
run a learn phase first — and without any `download-config.yml`.

## Three-tier CI

The download-then-order pattern works best when combined with tiered test
execution: run only change-affected tests first, then the highest-scored
remaining tests, and finally everything else — only if earlier tiers pass.

```yaml
- name: "Tier 1: Change-affected tests"
  run: |
    mvn test-order:tiered-select test \
      -Dtestorder.changeMode=since-last-commit \
      -Dtestorder.ci.githubStepSummary=true \
      -Dsurefire.failIfNoSpecifiedTests=false

- name: "Tier 2: Top-scored remaining"
  if: success()
  run: mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2 -Dsurefire.failIfNoSpecifiedTests=false

- name: "Tier 3: Full coverage"
  if: success()
  run: mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3 -Dsurefire.failIfNoSpecifiedTests=false
```

For a single-invocation alternative (one Surefire JVM, all tiers in sequence):

```bash
mvn test-order:run-tiered test -Dtestorder.tiered.tier2Fraction=0.5
```

**Parallel sharding** splits tier 3 across N runners (tiers 1 and 2 always
run in full on every runner):

```bash
# Runner 1 of 3 — tiers 1+2 in full, then 1/3 of tier 3
mvn test-order:run-tiered test -Dtestorder.tiered.shard=1/3
```

See the full annotated example at
[docs/ci-examples/github-actions-tiered-maven.yml](../docs/ci-examples/github-actions-tiered-maven.yml).
