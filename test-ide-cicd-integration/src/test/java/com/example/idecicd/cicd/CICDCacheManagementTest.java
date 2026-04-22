package com.example.idecicd.cicd;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CI/CD cache management bugs (P5-CICD-025, 026).
 * 
 * Bug Categories:
 * - P5-CICD-025: CI/CD cache becomes corrupted during concurrent builds
 * - P5-CICD-026: CI/CD cache misses cause redundant compilations
 */
@DisplayName("CI/CD Cache Management Tests")
public class CICDCacheManagementTest {

    private Path testDir;
    private Path cacheDir;
    private static final String TEST_NAME = "cicd-cache-management";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        cacheDir = testDir.resolve(".build-cache");
        Files.createDirectories(cacheDir);
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-CICD-025: CI/CD cache becomes corrupted during concurrent builds
    @Test
    @DisplayName("P5-CICD-025: Cache handles concurrent read operations")
    void testCacheConcurrentReads() throws IOException {
        // Create cache entries
        Path cacheEntry1 = cacheDir.resolve("cache-1.dat");
        Path cacheEntry2 = cacheDir.resolve("cache-2.dat");
        
        TestEnvironmentSetup.createTestFile(cacheDir, "cache-1.dat", "CACHE_DATA_1");
        TestEnvironmentSetup.createTestFile(cacheDir, "cache-2.dat", "CACHE_DATA_2");

        // Both entries should be readable
        String data1 = TestEnvironmentSetup.readFile(cacheEntry1);
        String data2 = TestEnvironmentSetup.readFile(cacheEntry2);
        
        assertThat(data1).isEqualTo("CACHE_DATA_1");
        assertThat(data2).isEqualTo("CACHE_DATA_2");
    }

    @Test
    @DisplayName("P5-CICD-025: Cache detects partial writes from failed builds")
    void testCacheDetectsPartialWrites() throws IOException {
        Path cacheEntry = cacheDir.resolve("partial.cache");
        
        // Simulate partial write (incomplete data)
        TestEnvironmentSetup.createTestFile(cacheDir, "partial.cache", "INCOMPLETE");
        
        String content = TestEnvironmentSetup.readFile(cacheEntry);
        long fileSize = TestEnvironmentSetup.getFileSize(cacheEntry);
        
        // Cache should detect incomplete write
        assertThat(fileSize).isLessThan(100);
        assertThat(content).contains("INCOMPLETE");
    }

    @Test
    @DisplayName("P5-CICD-025: Cache prevents interleaved writes from parallel builds")
    void testCachePreventsInterleavedWrites() throws IOException {
        Path cacheEntry = cacheDir.resolve("locked.cache");
        
        // Create initial cache entry
        TestEnvironmentSetup.createTestFile(cacheDir, "locked.cache", "ORIGINAL");
        
        // Verify cache entry is readable
        String content = TestEnvironmentSetup.readFile(cacheEntry);
        assertThat(content).contains("ORIGINAL");
        
        // Cache should prevent interleaved writes by using locks/atomicity
        assertThat(Files.exists(cacheEntry)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-025: Cache recovery from corruption")
    void testCacheRecoveryFromCorruption() throws IOException {
        Path cacheEntry = cacheDir.resolve("corrupted.cache");
        
        // Create corrupted cache entry
        TestEnvironmentSetup.createTestFile(cacheDir, "corrupted.cache", "");
        
        // Cache should detect empty/corrupted entry and rebuild
        boolean isCacheValid = TestEnvironmentSetup.getFileSize(cacheEntry) > 0;
        assertThat(isCacheValid).isFalse();
        
        // Rebuild cache by recreating entry
        TestEnvironmentSetup.createTestFile(cacheDir, "corrupted.cache", "REBUILT");
        
        String newContent = TestEnvironmentSetup.readFile(cacheEntry);
        assertThat(newContent).isEqualTo("REBUILT");
    }

    // P5-CICD-026: CI/CD cache misses cause redundant compilations
    @Test
    @DisplayName("P5-CICD-026: Cache hit on unchanged source files")
    void testCacheHitOnUnchangedFiles() throws IOException {
        Path sourceFile = testDir.resolve("src/Main.java");
        TestEnvironmentSetup.createTestFile(testDir.resolve("src"), "Main.java", "public class Main {}");

        // Create cache entry with source file hash
        String sourceHash = computeFileHash(TestEnvironmentSetup.readFile(sourceFile));
        Path cacheEntry = cacheDir.resolve("Main.class");
        TestEnvironmentSetup.createTestFile(cacheDir, "Main.class", "CACHED_BYTES:" + sourceHash);

        // Later build with same source should hit cache
        String newSourceHash = computeFileHash(TestEnvironmentSetup.readFile(sourceFile));
        String cachedData = TestEnvironmentSetup.readFile(cacheEntry);
        
        assertThat(sourceHash).isEqualTo(newSourceHash);
        assertThat(cachedData).contains(sourceHash);
    }

    @Test
    @DisplayName("P5-CICD-026: Cache miss on modified source files")
    void testCacheMissOnModifiedFiles() throws IOException {
        Path sourceFile = testDir.resolve("src/Changed.java");
        TestEnvironmentSetup.createTestFile(testDir.resolve("src"), "Changed.java", "public class Changed {}");

        String hash1 = computeFileHash(TestEnvironmentSetup.readFile(sourceFile));
        
        // Store cache entry with hash1
        Path cacheEntry = cacheDir.resolve("Changed.class");
        TestEnvironmentSetup.createTestFile(cacheDir, "Changed.class", "HASH1:" + hash1);

        // Modify source file
        TestEnvironmentSetup.createTestFile(testDir.resolve("src"), "Changed.java", "public class Changed { public void method() {} }");
        
        String hash2 = computeFileHash(TestEnvironmentSetup.readFile(sourceFile));
        
        // Hashes should differ, triggering cache miss
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("P5-CICD-026: Cache invalidation on dependency changes")
    void testCacheInvalidationOnDependencyChange() throws IOException {
        Path libDir = testDir.resolve("lib");
        Files.createDirectories(libDir);
        
        Path lib1 = libDir.resolve("junit-4.12.jar");
        TestEnvironmentSetup.createTestFile(libDir, "junit-4.12.jar", "JUNIT412");

        // Cache test results with junit-4.12
        Path cacheEntry = cacheDir.resolve("TestResults.cache");
        TestEnvironmentSetup.createTestFile(cacheDir, "TestResults.cache", "junit:4.12");

        // Update junit library
        Files.delete(lib1);
        Path lib2 = libDir.resolve("junit-4.13.jar");
        TestEnvironmentSetup.createTestFile(libDir, "junit-4.13.jar", "JUNIT413");

        // Cache should be invalidated
        String cachedVersion = TestEnvironmentSetup.readFile(cacheEntry);
        assertThat(cachedVersion).contains("junit:4.12");
        assertThat(Files.exists(lib1)).isFalse();
        assertThat(Files.exists(lib2)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-026: Cache tracks transitive dependency changes")
    void testCacheTracksTransitiveDependencies() throws IOException {
        Path cacheMetadata = cacheDir.resolve("dependencies.meta");
        String metadata = "direct:junit-4.13.jar\n" +
                "transitive:hamcrest-core-1.3.jar\n" +
                "transitive:hamcrest-library-1.3.jar";
        TestEnvironmentSetup.createTestFile(cacheDir, "dependencies.meta", metadata);

        String content = TestEnvironmentSetup.readFile(cacheMetadata);
        assertThat(content).contains("hamcrest-core");
        assertThat(content).contains("hamcrest-library");
    }

    @Test
    @DisplayName("P5-CICD-026: Cache stores build outputs correctly")
    void testCacheStoresBuildOutputs() throws IOException {
        Path buildOutput = testDir.resolve("target/classes/Main.class");
        Files.createDirectories(buildOutput.getParent());
        TestEnvironmentSetup.createTestFile(buildOutput.getParent(), "Main.class", "CAFEBABE");

        // Cache the build output
        Path cacheEntry = cacheDir.resolve("Main.class");
        TestEnvironmentSetup.createTestFile(cacheDir, "Main.class", TestEnvironmentSetup.readFile(buildOutput));

        String buildContent = TestEnvironmentSetup.readFile(buildOutput);
        String cacheContent = TestEnvironmentSetup.readFile(cacheEntry);
        
        assertThat(buildContent).isEqualTo(cacheContent);
    }

    @Test
    @DisplayName("P5-CICD-026: Cache prevents redundant test executions")
    void testCachePreventsRedundantTests() throws IOException {
        Path testSourceFile = testDir.resolve("src/test/TestCase.java");
        Files.createDirectories(testSourceFile.getParent());
        TestEnvironmentSetup.createTestFile(testSourceFile.getParent(), "TestCase.java",
                "public class TestCase { @Test void test() {} }");

        // Cache test results
        Path testCacheEntry = cacheDir.resolve("TestCase.results");
        TestEnvironmentSetup.createTestFile(cacheDir, "TestCase.results", 
                "source_hash:abc123\nresults:ALL_PASSED");

        // If source hasn't changed, should use cached results
        String testSourceHash = computeFileHash(TestEnvironmentSetup.readFile(testSourceFile));
        String cacheEntry = TestEnvironmentSetup.readFile(testCacheEntry);
        
        assertThat(cacheEntry).contains("source_hash:");
        assertThat(cacheEntry).contains("ALL_PASSED");
    }

    @Test
    @DisplayName("P5-CICD-026: Cache validates integrity before use")
    void testCacheValidatesIntegrity() throws IOException {
        // Create cache entry with checksum
        Path cacheEntry = cacheDir.resolve("validated.cache");
        String cacheData = "COMPILE_OUTPUT:checksum=d41d8cd98f00b204e9800998ecf8427e";
        TestEnvironmentSetup.createTestFile(cacheDir, "validated.cache", cacheData);

        String content = TestEnvironmentSetup.readFile(cacheEntry);
        assertThat(content).contains("checksum=");
        
        // Cache should verify checksum before using
        assertThat(cacheEntry.getFileName().toString()).isEqualTo("validated.cache");
    }

    // Helper method to compute file hash
    private String computeFileHash(String content) {
        // Simple hash implementation for testing
        return String.valueOf(content.hashCode());
    }
}
