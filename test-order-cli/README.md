# test-order-cli

Command-line tool for downloading and caching CI dependency files from GitHub Actions or HTTP endpoints.

## Overview

`test-order-cli` provides a unified interface for downloading test dependencies from various CI systems:
- **GitHub Actions**: Fetch artifacts from workflow runs
- **HTTP**: Generic HTTP endpoint support with authentication
- **Local Caching**: Efficient caching with metadata tracking and cleanup

## Features

- ✅ **GitHub Actions Integration**: Download artifacts from successful workflow runs
- ✅ **HTTP Downloader**: Generic HTTP endpoint support (bearer token, basic auth, or no auth)
- ✅ **YAML Configuration**: Simple `.test-order-ci.yml` config format
- ✅ **Local Caching**: Automatic caching with checksum verification
- ✅ **Token Resolution**: Read tokens from environment variables
- ✅ **Error Handling**: Comprehensive error handling with helpful messages

## Installation

Add to your project's root `pom.xml`:

```xml
<modules>
    <module>test-order-cli</module>
</modules>
```

## Configuration

Create `.test-order-ci.yml` in your project root:

### GitHub Actions Configuration

```yaml
ci:
  github:
    owner: your-org
    repo: your-repo
    workflow: ci.yml
    artifact-name: test-deps
    branch: main
```

The tool will:
1. Query GitHub API for successful runs of `ci.yml` on `main` branch
2. Find the artifact named `test-deps`
3. Download it to `.test-order-cache/`
4. Automatically use the cached version on subsequent runs

**Token**: Reads `GITHUB_TOKEN` or `GH_TOKEN` from environment

### HTTP Configuration

```yaml
ci:
  http:
    url: https://ci.example.com/artifacts/deps.zip
    auth: bearer
    token-env: CI_TOKEN
```

**Supported auth schemes**:
- `bearer` - Bearer token authentication
- `basic` - Basic authentication  
- (omit or `none`) - No authentication

**Token**: Reads from environment variable specified in `token-env`

## Usage

### In Code

```java
CiConfig config = CiConfigParser.parse(Files.readString(Paths.get(".test-order-ci.yml")));
CiDepDownloadManager manager = new CiDepDownloadManager(config);

Path depsFile = manager.download();
// Use depsFile...

// List cached artifacts
manager.listCache();

// Clean old versions (keep 5 latest)
manager.cleanupCache(5);

// Clear all cache
manager.clearCache();
```

### In Maven

```bash
# Download with custom config
java -cp target/test-order-cli-0.1.0-SNAPSHOT.jar \
  me.bechberger.testorder.cli.DepDownloadCLI download \
  --config .test-order-ci.yml
```

## Architecture

### Core Components

**CiConfig / CiConfigParser**
- Parses YAML configuration files
- Supports both GitHub Actions and HTTP configurations
- Validates required fields

**DepDownloader (Interface)**
- `GitHubActionsDownloader`: Implements GitHub REST API integration
- `HttpDownloader`: Implements generic HTTP downloads

**ArtifactCache**
- Local caching in `.test-order-cache/`
- Metadata tracking (filename, source, timestamp, checksum)
- Automatic cleanup based on age/count

**CiDepDownloadManager**
- Orchestrates config parsing, downloader selection, and caching
- Environment variable token resolution
- High-level API for download operations

### Test Coverage

**58 comprehensive tests** covering:

| Test Class | Count | Coverage |
|-----------|-------|----------|
| CiConfigParserTest | 9 | YAML parsing, validation |
| CiIntegrationTest | 11 | MockWebServer-based integration |
| DownloaderTests | 7 | GitHub API, HTTP functionality |
| ArtifactCacheTest | 3 | Caching and cleanup |
| MockedCiDownloaderTest | 21 | Config validation, edge cases |
| **HttpErrorHandlingTests** | **7** | **HTTP errors, auth headers** |
| **Total** | **58** | **100% passing** |

## Error Handling

### GitHub Actions Errors

```
No successful workflow runs found for ci.yml on branch main
Artifact 'test-deps' not found in workflow run 12345
GitHub API request failed: 401 Unauthorized
```

### HTTP Errors

```
HTTP request failed: 404 Not Found
Invalid URL: https://invalid...
Response body is empty
```

### Configuration Errors

```
Invalid HTTP config: missing url
GitHub owner is required
Invalid YAML structure
```

## Security Considerations

- ✅ Tokens read from environment variables only (never hardcoded)
- ✅ Supports bearer token and basic auth
- ✅ YAML configs should **not** contain tokens (use `token-env` instead)
- ✅ Cache files stored in `.test-order-cache/` directory (add to `.gitignore`)
- ⚠️ Use `HTTPS` URLs only in production

## Examples

### Download from GitHub Actions

```yaml
# .test-order-ci.yml
ci:
  github:
    owner: bechberger
    repo: test-order
    workflow: integration-tests.yml
    artifact-name: test-order-deps
    branch: main
```

```bash
export GITHUB_TOKEN=ghp_xxxx...
java -jar test-order-cli.jar download
```

### Download from HTTP with Bearer Token

```yaml
ci:
  http:
    url: https://internal-ci.example.com/api/artifacts/deps.zip
    auth: bearer
    token-env: CI_API_TOKEN
```

```bash
export CI_API_TOKEN=secret-token
java -jar test-order-cli.jar download
```

### Public HTTP Download (No Auth)

```yaml
ci:
  http:
    url: https://cdn.example.com/test-deps.zip
```

## Dependencies

- **okhttp3**: HTTP client (3.x)
- **gson**: JSON parsing
- **snakeyaml**: YAML parsing
- **mockwebserver**: Testing (test scope)
- **junit5**: Testing framework (test scope)

## Troubleshooting

### "No token found"
- Ensure environment variable is set: `export GITHUB_TOKEN=...`
- Check `token-env` in config matches your environment variable name

### "HTTP request failed: 403"
- Token may have insufficient permissions
- For GitHub: token needs `repo` and `actions` scopes
- Check token is still valid (hasn't expired)

### "Cache directory not writable"
- Ensure `.test-order-cache/` directory can be created
- Check file permissions in your home directory

### "Malformed JSON response"
- Verify endpoint URL is correct
- Check endpoint returns valid JSON (not HTML error page)

## Future Enhancements

- [ ] Support for GitLab CI artifacts
- [ ] Support for Azure Pipelines
- [ ] Parallel downloads for multiple artifacts
- [ ] Compression (gzip/brotli) support
- [ ] HTTP proxy support
- [ ] Resume interrupted downloads
- [ ] Rate limit handling and backoff
- [ ] Webhook-triggered cache invalidation

## Development

### Running Tests

```bash
mvn test -pl test-order-cli
```

### With Code Coverage

```bash
mvn clean verify -pl test-order-cli
```

### Running Specific Tests

```bash
mvn test -pl test-order-cli -Dtest=HttpErrorHandlingTests
```

## Bug Fixes (Latest Release)

Fixed 8 bugs in HTTP handling and caching:

1. ✅ Missing null check on `response.body()` 
2. ✅ Missing null check on `JsonArray.get()`
3. ✅ Gson type casting error in metadata deserialization
4. ✅ Exception handling in forEach lambda
5. ✅ Missing token null validation in getEnv()
6. ✅ Missing `StandardCopyOption.REPLACE_EXISTING` in Files.copy()
7. ✅ Resource leak in Files.walk()
8. ✅ Bearer vs Basic auth header formatting

See [BUG_FIXES_TWO_NEW_MODULES.md](../BUG_FIXES_TWO_NEW_MODULES.md) for details.

## License

See [LICENSE](../LICENSE)
