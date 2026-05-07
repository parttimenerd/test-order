# test-order-ci

Downloads pre-built dependency indexes from CI systems so that feature branches
skip the cold-start learn phase. Integrated into the Maven and Gradle plugins —
when a `.test-order/download-config.yml` exists, **auto mode** downloads the index
automatically before falling back to learn mode.

## Supported providers

| Provider | Config key | Auth |
|----------|-----------|------|
| GitHub Actions | `ci.github` | `GITHUB_TOKEN` env var |
| GitLab CI | `ci.gitlab` | Token env var (configurable) |
| Generic HTTP | `ci.http` | Bearer / Basic / none |

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

## Usage

### Maven

```bash
# Standalone goal — download the index from CI
mvn test-order:download

# Auto mode — downloads automatically when config is present
mvn test -Dtestorder.mode=auto
```

### Gradle

```bash
# Standalone task
./gradlew testOrderDownload

# Auto mode — downloads automatically when config is present
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

      # ── PR branches: download index, then order ─────────────
      - name: Run tests (auto — downloads index from main)
        if: github.ref != 'refs/heads/main'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: mvn verify -Dtestorder.mode=auto
```

### `.test-order/download-config.yml`

```yaml
ci:
  github:
    owner: acme
    repo: shop-service
    workflow: ci.yml
    artifact-name: test-order-deps
    branch: main
```

With this setup every PR gets fast, priority-ordered tests without having to
run a learn phase first.
