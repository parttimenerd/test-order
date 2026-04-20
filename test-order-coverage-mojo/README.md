# test-order-coverage-mojo

Maven plugin for analyzing test coverage and identifying least-tested classes across a multi-module project.

## Overview

`test-order-coverage-mojo` provides comprehensive coverage analysis by combining:
- **JaCoCo reports** (line, method, branch coverage metrics)
- **Surefire reports** (test execution counts and names)
- **Multi-module aggregation** (analyzes all modules in a reactor build)

The plugin generates human-readable Markdown reports and JSON metrics suitable for CI/CD integration.

## Installation

Add to your root `pom.xml`:

```xml
<modules>
    <module>test-order-coverage-mojo</module>
</modules>
```

The module is automatically included in the multi-module build.

## Usage

### Basic Usage

Run coverage analysis with default settings (50% threshold):

```bash
mvn test-order:coverage
```

This generates reports in `target/coverage-reports/`:
- `COVERAGE_BY_MODULE.md` - Module-level coverage summary
- `LEAST_TESTED_CLASSES.md` - Classes below threshold, grouped by severity
- `COVERAGE_RECOMMENDATIONS.md` - Actionable improvement recommendations
- `coverage-metrics.json` - Machine-readable metrics for CI systems

### Custom Threshold

Analyze classes below 40% coverage:

```bash
mvn test-order:coverage -Dcoverage.threshold=40
```

### Custom Output Directory

Write reports to a specific location:

```bash
mvn test-order:coverage -Dcoverage.outputDir=./coverage-analysis
```

### Filter by Module

Analyze only specific modules:

```bash
mvn test-order:coverage -Dcoverage.includeModules=test-order-core,test-order-cli
```

### Output Format

Generate only JSON output:

```bash
mvn test-order:coverage -Dcoverage.outputFormat=json
```

Supported formats:
- `comprehensive` (default) - All reports
- `json` - Coverage metrics JSON only
- `module` - Module summary Markdown only
- `least-tested` - Least tested classes Markdown only

## Report Examples

### COVERAGE_BY_MODULE.md

```markdown
# Coverage by Module

| Module | Avg Coverage | Classes | High (≥80%) | Medium (50-80%) | Low (<50%) |
|--------|----------|---------|-------------|-----------------|-----------|
| test-order-core | 78% | 45 | 35 | 8 | 2 |
| test-order-cli | 72% | 12 | 8 | 3 | 1 |
| **Overall** | **77%** | **65** | **50** | **12** | **3** |
```

### LEAST_TESTED_CLASSES.md

```markdown
# Coverage Summary

## Overall Metrics

- **Average Coverage**: 77%
- **Minimum Coverage**: 15%
- **Total Classes**: 65

## Critical (< 30%)
- `com.example.CachingStrategy` (23%) [Module: test-order-core] Tests: 1

## Important (30-50%)
- `com.example.GraduatingFilter` (41%) [Module: test-order-core] Tests: 2
```

### COVERAGE_RECOMMENDATIONS.md

```markdown
# Coverage Improvement Recommendations

## Priority 1: Critical Classes (< 30%)

These classes have minimal test coverage and should be prioritized:

- **com.example.CachingStrategy**: Add 7+ tests to reach 50% coverage

## Priority 2: Important Classes (30-50%)

These classes have low coverage and should be addressed next:

- **com.example.GraduatingFilter**: Currently at 41% coverage
```

### coverage-metrics.json

```json
{
  "timestamp": 1713613461123,
  "threshold": 50,
  "moduleStatistics": {
    "test-order-core": {
      "avgCoverage": "78%",
      "classCount": 45,
      "high": 35,
      "medium": 8,
      "low": 2
    },
    "test-order-cli": {
      "avgCoverage": "72%",
      "classCount": 12,
      "high": 8,
      "medium": 3,
      "low": 1
    }
  }
}
```

## Architecture

### Core Components

- **ClassMetrics** - Immutable value object representing single-class coverage metrics
- **CoverageReport** - Aggregation layer enabling module/project-level analysis
- **JaCoCoReportParser** - Parses JaCoCo XML reports (line/method/branch coverage)
- **SurefireReportParser** - Parses Surefire test reports (test counts)
- **LeastTestedClassifier** - Filters and ranks classes by coverage, groups by severity
- **CoverageReporter** - Generates analytical summaries and recommendations
- **MarkdownGenerator** - Produces human and machine-readable output
- **CoverageMojo** - Maven plugin entry point

### Severity Levels

Classes are automatically categorized:
- **Critical** (<30%) - Urgent attention required
- **Important** (30-50%) - Should be addressed soon
- **Should Review** (50-70%) - Good to improve
- **Acceptable** (≥70%) - Adequate coverage

## Requirements

- Maven 3.6.0+
- JDK 17+
- JaCoCo Maven plugin (for coverage report generation)
- Maven Surefire plugin (for test execution)

## Configuration

### pom.xml Dependencies

The module requires:
- `maven-plugin-api` (Maven plugin development)
- `jacoco-core` (JaCoCo report parsing)
- `gson` (JSON output)

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `coverage.threshold` | int | 50 | Coverage percentage threshold (0-100) |
| `coverage.outputDir` | File | target/coverage-reports | Output directory for reports |
| `coverage.outputFormat` | String | comprehensive | Report format (comprehensive/json/module/least-tested) |
| `coverage.includeModules` | String | - | Comma-separated module names to analyze (all if omitted) |

## Example CI Integration

### GitHub Actions

```yaml
- name: Analyze Test Coverage
  run: mvn test test-order:coverage
  
- name: Upload Coverage Report
  uses: actions/upload-artifact@v3
  with:
    name: coverage-reports
    path: target/coverage-reports/
```

### GitLab CI

```yaml
coverage-analysis:
  script:
    - mvn test test-order:coverage
  artifacts:
    paths:
      - target/coverage-reports/
    expire_in: 30 days
```

## Testing

### Unit Tests

**26 comprehensive unit tests** validate:
- JaCoCo report parsing (4 tests)
- Surefire test count extraction (included in parser)
- Least-tested classification and filtering (7 tests)
- Coverage reporting and statistics (7 tests)
- Markdown/JSON output generation (8 tests)

Run tests:

```bash
mvn test -pl test-order-coverage-mojo
```

### Test Classes

- **JaCoCoReportParserTest** - Validates XML parsing, coverage percentage calculation, package/class hierarchy
- **LeastTestedClassifierTest** - Validates severity classification (Critical/Important/Review/Acceptable), module/package grouping, abstract/interface/enum filtering
- **CoverageReporterTest** - Validates report generation, statistics calculation, module/class filtering
- **MarkdownGeneratorTest** - Validates file I/O, format correctness, JSON serialization

### Test Pass Rate

✅ **100%** - All 26 tests passing consistently

## Bug Fixes (Latest Release)

Fixed 1 bug in SurefireReportParser:

- ✅ Resource leak in Files.walk() - wrapped in try-with-resources

See [BUG_FIXES_TWO_NEW_MODULES.md](../BUG_FIXES_TWO_NEW_MODULES.md) for complete details on all 8 bugs fixed across both modules.

## Troubleshooting

### No Coverage Data Found

Ensure JaCoCo reports are generated:

```bash
mvn clean test jacoco:report test-order:coverage
```

Or add JaCoCo to your pom.xml:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Empty Reports

Ensure Surefire tests are executed:

```bash
mvn test  # Generates surefire-reports/
mvn test-order:coverage
```

## Future Enhancements

- HTML report generation
- Coverage trend tracking over time
- SonarQube integration
- Gradle plugin support
- Coverage delta analysis (PR-specific)
- Custom severity thresholds
- Method-level coverage metrics

## License

MIT - See root LICENSE file

## Contributing

See root CONTRIBUTING.md

## Author

Built with test-order framework by Johannes Bechberger
