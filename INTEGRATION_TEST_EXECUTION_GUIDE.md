# INTEGRATION TEST EXECUTION GUIDE

**Test Suite**: Test-Order Maven/Gradle/CLI Integration Tests  
**Created**: April 21, 2026  
**Location**: `test-order-agent/src/test/java/me/bechberger/testorder/integration/`

---

## QUICK START

### Run All Integration Tests
```bash
cd /Users/i560383_1/code/experiments/test-order
mvn test -Dtest="me.bechberger.testorder.integration.*" -DfailIfNoTests=false
```

### Run Specific Test Category
```bash
# Maven + CLI only
mvn test -Dtest=MavenCliIntegrationTest

# Gradle + CLI only
mvn test -Dtest=GradleCliIntegrationTest

# CI/CD only
mvn test -Dtest=CICDIntegrationTest

# Data Consistency only
mvn test -Dtest=DataConsistencyIntegrationTest
```

### Run Single Test
```bash
mvn test -Dtest=MavenCliIntegrationTest#testCacheLocationConflicts
```

---

## TEST CATEGORIES OVERVIEW

## 1. MAVEN + CLI INTEGRATION TESTS
**File**: `MavenCliIntegrationTest.java`  
**Total Tests**: 8  
**Estimated Runtime**: 30 seconds

### Test Descriptions

#### INT-M-CLI-001: CLI Downloads Dependencies for Maven Plugin
```
Test: testCliDownloadsDependenciesForMavenPlugin()
Purpose: Verify CLI can cache artifacts for Maven plugin
Expected: Files written to and read from Maven cache location
Steps:
  1. Create CLI config pointing to Maven cache dir
  2. Create cache directory
  3. Verify files exist
Pass Condition: Cache files created successfully
```

#### INT-M-CLI-002: Config File Precedence Across Tools
```
Test: testConfigFilePrecedenceOrder()
Purpose: Verify config precedence is consistent
Expected: Project config > User config > System config
Steps:
  1. Create config files at different levels
  2. Verify precedence order
Pass Condition: Project-level config found first
Issue: Precedence not fully documented
```

#### INT-M-CLI-003: Token Handling Across Maven and CLI
```
Test: testTokenHandlingAcrossTools()
Purpose: Verify same token works in both tools
Issues: 
  - Token stored in different formats (Maven: encrypted, CLI: plain)
  - Security inconsistency detected
Risk: CRITICAL
Steps:
  1. Create CLI config with token
  2. Create Maven settings.xml with token
  3. Verify both contain token
Pass Condition: Token accessible in both
Root Cause: Different token storage mechanisms
Recommendation: Implement unified token handling
```

#### INT-M-CLI-004: Cache Location Conflicts
```
Test: testCacheLocationConflicts()
Purpose: Verify no data corruption in shared cache
Expected: Maven and CLI cache files coexist without issues
Steps:
  1. Create shared cache directory
  2. Maven writes cache file
  3. CLI writes different cache file
  4. Verify both files intact
Pass Condition: Both files readable and uncorrupted
```

#### INT-M-CLI-005: Concurrent Usage - Parallel Access
```
Test: testConcurrentMavenAndCliAccess()
Purpose: Detect race conditions in cache access
Risk: CRITICAL
Issues Found: Race conditions possible
Scenario:
  1. Maven plugin writes to cache (10 iterations)
  2. CLI tool writes to cache (10 iterations) - simultaneously
  3. Verify final state consistent
Root Cause: No file-level locking mechanism
Impact: Cache corruption in high-concurrency CI
Recommendation:
  - Implement file locks (.lock files)
  - Use atomic file operations (temp → rename)
  - Add cache integrity verification
```

#### INT-M-CLI-006: Maven Reads CLI-Downloaded Artifacts
```
Test: testMavenPluginReadsCLIDownloadedArtifacts()
Purpose: Verify Maven can consume CLI-downloaded artifacts
Expected: Maven finds artifacts in shared repo
Steps:
  1. CLI simulates downloading artifact
  2. Maven plugin locates artifact
  3. Verify artifact readable
Pass Condition: Artifact accessible to Maven
```

#### INT-M-CLI-007: Config Format Compatibility
```
Test: testConfigFormatCompatibility()
Purpose: Verify configs compatible across formats
Issues:
  - Maven: XML pom.xml + .properties
  - CLI: YAML .test-order.yml
  - No auto-conversion tool
Impact: Users must manually sync values
Recommendation: Provide conversion tool
```

#### INT-M-CLI-008: Environment Variable Override
```
Test: testEnvironmentVariableOverridePriority()
Purpose: Verify env vars override file config
Expected: Both tools recognize env vars
Steps:
  1. Set TEST_ORDER_* environment variables
  2. Run both tools
  3. Verify env vars take precedence
Pass Condition: Env vars respected
```

---

## 2. GRADLE + CLI INTEGRATION TESTS
**File**: `GradleCliIntegrationTest.java`  
**Total Tests**: 8  
**Estimated Runtime**: 30 seconds
**Warning**: Some tests blocked by Gradle Java 26 issue

### Test Descriptions

#### INT-G-CLI-001: CLI Artifacts Available to Gradle
```
Test: testCliArtifactsAvailableToGradle()
Purpose: Verify Gradle can access CLI-cached artifacts
Expected: Gradle cache contains CLI artifacts
Steps:
  1. Create Gradle cache directory
  2. Simulate CLI downloading artifact
  3. Verify Gradle can find artifact
Pass Condition: Artifact accessible to Gradle
```

#### INT-G-CLI-002: Configuration Coordination
```
Test: testConfigurationCoordination()
Purpose: Verify configuration synchronized across tools
Issues:
  - build.gradle uses Groovy/Kotlin DSL
  - CLI uses YAML
  - No bidirectional sync
Impact: Configuration drift possible
Recommendation: 
  - Gradle plugin should generate CLI config
  - Or: Sync mechanism needed
```

#### INT-G-CLI-003: Cache Sharing Between Gradle and CLI
```
Test: testCacheSharing()
Purpose: Verify shared cache access works
Expected: Both tools can read/write cache
Steps:
  1. Create shared cache directory
  2. Gradle writes test scores
  3. CLI reads and updates scores
  4. Verify data integrity
Pass Condition: Both operations succeed
```

#### INT-G-CLI-004: Version Compatibility Check
```
Test: testVersionCompatibility()
Purpose: Verify tool versions compatible
CRITICAL ISSUE FOUND:
  - Gradle plugin compiled with Java 26
  - CLI compatible with Java 17
  - Incompatible on Java 21 systems
Error: "Unsupported class file major version 70"
Impact: Gradle plugin completely unusable on Java 21
Recommendation: Recompile with Java 21 target
```

#### INT-G-CLI-005: Gradle Uses CLI Configuration
```
Test: testGradlePluginUsesCLIConfiguration()
Purpose: Verify Gradle can parse CLI-generated config
Expected: Configuration file parseable
Steps:
  1. CLI generates property file
  2. Gradle plugin reads configuration
  3. Verify all settings loaded
Pass Condition: Config fully parsed
```

#### INT-G-CLI-006: Concurrent Gradle Build + CLI Download
```
Test: testConcurrentGradleAndCliOperations()
Purpose: Detect interference during parallel operations
Risk: HIGH
Scenario:
  1. Gradle build running (writing artifacts)
  2. CLI tool downloading (writing artifacts) - simultaneously
  3. Both complete without error
Issue: No coordination mechanism
Recommendation: Implement build-level locking
```

#### INT-G-CLI-007: Incremental Build with Cache
```
Test: testGradleIncrementalBuildWithCLICache()
Purpose: Verify cache reuse improves performance
Expected: Second build faster than first
Steps:
  1. CLI downloads artifact (first build)
  2. Second build uses cache
  3. Verify artifact exists and unchanged
Pass Condition: Artifact reused successfully
```

#### INT-G-CLI-008: Error When CLI Config Missing
```
Test: testGradleErrorWhenCLIConfigMissing()
Purpose: Verify clear error message when config missing
Current Error: "Configuration not found: .test-order.yml"
Recommended: Include remediation steps
Better Error:
  "CLI configuration not found: .test-order.yml
   To fix:
     1. Run: test-order-cli init
     2. Or: Configure in build.gradle
     3. Or: Set env: TEST_ORDER_CONFIG=..."
```

---

## 3. CI/CD INTEGRATION TESTS
**File**: `CICDIntegrationTest.java`  
**Total Tests**: 15  
**Estimated Runtime**: 45 seconds
**Note**: Some tests require actual CI environment

### Test Descriptions (Critical Tests)

#### INT-CI-005: Parallel CI Jobs Accessing Cache
```
Test: testParallelCIJobsAccessingCache()
Purpose: Detect race conditions in parallel CI
Risk: CRITICAL
Scenario:
  1. CI runs matrix build (multiple parallel jobs)
  2. All jobs access shared cache
  3. Verify no corruption
Issues Found:
  - No distributed locking
  - Race conditions possible
  - Cache state can be corrupted
Impact: Wrong test results in CI
Example: GitHub Actions matrix with 8 parallel jobs
Recommendation:
  - File-level locking (.lock files)
  - Distributed locking (Redis/etcd)
  - Cache versioning
```

#### INT-CI-007: Disk Full During Cache Write
```
Test: testDiskFullDuringCacheWrite()
Purpose: Verify graceful handling of disk full
Risk: CRITICAL
Scenario:
  1. Available disk space near zero
  2. Tool tries to write cache
  3. Write fails mid-operation
  4. Verify cache not corrupted
Issues Found:
  - Tools might corrupt cache on disk full
  - Next run fails silently or with wrong data
Impact: Silent cache corruption
Recommendation:
  - Check disk space before write
  - Atomic write pattern (temp file → move)
  - Verify written data
  - Cache integrity checksums
```

#### INT-CI-014: Matrix Build Different Java Versions
```
Test: testMatrixBuildDifferentJavaVersions()
Purpose: Verify cache works across Java versions
Scenario:
  - CI matrix: Java 17, 21, 26
  - All share same cache
Issue: Cache not tagged with Java version
  - Tool v0.1.0 (Java 17) creates cache
  - Tool v0.2.0 (Java 21) reads cache - compatibility unknown
  - Gradle plugin (Java 26) incompatible
Impact: Undefined behavior, possibly wrong results
Recommendation:
  - Add Java version to cache metadata
  - Warn on version mismatch
  - Fix Gradle Java 26 issue
```

### Running CI Tests

#### Local Simulation
```bash
mvn test -Dtest=CICDIntegrationTest
```

#### Full CI Validation (Requires CI)
To validate in real CI system:

**GitHub Actions**:
```yaml
- name: Run integration tests
  run: mvn test -Dtest="CICDIntegrationTest"
```

**GitLab CI**:
```yaml
integration_tests:
  script:
    - mvn test -Dtest="CICDIntegrationTest"
```

**Jenkins**:
```groovy
stage('Integration Tests') {
    steps {
        sh 'mvn test -Dtest="CICDIntegrationTest"'
    }
}
```

---

## 4. DATA CONSISTENCY TESTS
**File**: `DataConsistencyIntegrationTest.java`  
**Total Tests**: 12  
**Estimated Runtime**: 1 minute

### Critical Tests

#### INT-CONS-004: Version Mismatch Handling
```
Test: testVersionMismatchHandling()
Purpose: Verify tools handle version mismatches
Risk: CRITICAL
Scenario:
  1. Cache created by v0.1.0
  2. Tool is v0.2.0
  3. Cache format might not be compatible
Issues Found:
  - No version negotiation protocol
  - Tools might fail silently
  - Data corruption possible
Impact: Undefined behavior on version upgrades
Recommendation:
  - Add version field to cache format
  - Implement migration strategy
  - Document upgrade path
  - Detect mismatches early
```

#### INT-CONS-006: Concurrent Read-Write Race
```
Test: testConcurrentReadWriteRaceCondition()
Purpose: Detect data corruption from concurrent access
Risk: CRITICAL
Scenario:
  1. Thread A writes cache file
  2. Thread B reads cache file
  3. Both operations simultaneous
Issues Found:
  - Reader might get partial/corrupted data
  - No synchronization primitives
  - Silent data corruption possible
Impact: Wrong test results in concurrent builds
Recommendation:
  - Read-write locks (ReentrantReadWriteLock)
  - Atomic file operations
  - Checksum verification
```

#### INT-CONS-008: Corrupted Cache Detection
```
Test: testCorruptedCacheFileDetection()
Purpose: Detect and handle cache corruption
Scenario:
  1. Cache file corrupted (invalid LZ4)
  2. Tool tries to load cache
  3. Verify graceful error handling
Issues Found:
  - Some corruption types detected
  - Silent corruption possible if format validates
  - No checksums in cache format
Impact: Wrong test results from corrupted cache
Recommendation:
  - Add CRC32 or SHA256 checksum
  - Verify on every load
  - Auto-recovery on detection
```

---

## RUNNING TESTS WITH DIFFERENT CONFIGURATIONS

### Run with Verbose Output
```bash
mvn test -Dtest="me.bechberger.testorder.integration.*" -X
```

### Run with Specific Java Version
```bash
# Use Java 21
/usr/libexec/java_home -v 21 --exec mvn test

# Use Java 26
/usr/libexec/java_home -v 26 --exec mvn test
```

### Run Specific Risk Level
```bash
# CRITICAL tests only
mvn test -Dtest="*IntegrationTest" \
  -Dgroups="CRITICAL"

# HIGH risk tests
mvn test -Dtest="*IntegrationTest" \
  -Dgroups="HIGH"
```

### Run with Code Coverage
```bash
mvn test -Dtest="*IntegrationTest" \
  -Dcoverage=true \
  -Dreports=html
```

---

## TEST ENVIRONMENT REQUIREMENTS

### Minimum Requirements
- Java 17+ (Java 21 for full compatibility)
- Maven 3.8.1+
- 2GB free disk space
- Network access (for some tests)

### Optional but Recommended
- Docker (for CI/CD tests)
- GitHub Actions runner (for full validation)
- Redis (for distributed locking tests)

### Environment Variables
```bash
# Optional configuration
export TEST_ORDER_CACHE=/tmp/test-order-cache
export TEST_ORDER_LOG_LEVEL=DEBUG
export TEST_ORDER_TIMEOUT=300
```

---

## TROUBLESHOOTING

### Tests Fail to Compile
**Error**: "unreported exception TimeoutException"
**Fix**: Ensure exception is declared in method signature
```java
void testMethod() throws TimeoutException {
```

### Tests Hang
**Cause**: Concurrent tests using Executor without shutdown  
**Fix**: Ensure `executor.shutdownNow()` in finally block

### Cache Test Failures
**Cause**: Previous test runs leave cache files
**Fix**: Clean temp directories
```bash
rm -rf /tmp/junit* /tmp/test-order*
```

### Java Version Errors
**Cause**: Gradle plugin Java 26 incompatibility
**Fix**: Use Java 21 or recompile Gradle plugin

### Permission Errors
**Cause**: Test trying to modify read-only files
**Fix**: Ensure test user has write permissions
```bash
chmod 755 /tmp/test-order*
```

---

## INTERPRETING RESULTS

### Test Status Codes
- ✅ **PASS**: Test completed successfully, no issues found
- ⚠️ **WARN**: Test passed but identified potential issues
- 🔴 **FAIL**: Test failed, critical issue identified

### Coverage Metrics
- **Functional Coverage**: Basic features work
- **Integration Coverage**: Tools work together
- **Resilience Coverage**: Handles edge cases
- **Security Coverage**: No token leaks, proper auth

---

## NEXT STEPS

### After Running Tests
1. **Review Critical Failures** (RED items)
2. **Address High-Priority Warnings** (ORANGE items)
3. **Document Findings** in GitHub issues
4. **Plan Fixes** by priority and complexity
5. **Retest** after fixes applied

### Creating New Tests
```bash
# Create new test class
touch test-order-agent/src/test/java/.../YourIntegrationTest.java
```

Template:
```java
package me.bechberger.testorder.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

@DisplayName("Your Integration Test Category")
class YourIntegrationTest {
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setup() {
        // Setup
    }
    
    @Test
    @DisplayName("INT-XXX-001: Your Test Description")
    void testYourScenario() throws IOException {
        // Arrange
        // Act
        // Assert
    }
}
```

---

## REPORTING ISSUES

When reporting test failures, include:
1. **Test ID**: (e.g., INT-M-CLI-005)
2. **Environment**: Java version, OS, CI system
3. **Steps to Reproduce**: Command run
4. **Expected vs Actual**: What should happen vs what did
5. **Error Output**: Full stack trace
6. **Severity**: CRITICAL, HIGH, MEDIUM, LOW
7. **Frequency**: Always, sometimes, one-time

Example:
```
Test ID: INT-CI-005
Environment: Java 21, macOS, local Maven
Steps: mvn test -Dtest=CICDIntegrationTest#testParallelCIJobsAccessingCache
Expected: Tests pass without errors
Actual: File corruption detected, cache integrity failed
Severity: CRITICAL
Frequency: Always reproducible
```

---

## CONTACT & SUPPORT

For test issues or improvements:
1. Check existing GitHub issues
2. Review test documentation
3. Run diagnostic: `mvn test -X -Dtest=...`
4. File issue with full environment details

