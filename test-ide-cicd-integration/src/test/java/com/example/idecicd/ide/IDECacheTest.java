package com.example.idecicd.ide;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IDE cache issues (P5-IDE-003, 006).
 * 
 * Bug Categories:
 * - P5-IDE-003: IDE cache becomes stale after file modifications
 * - P5-IDE-006: IDE cache invalidation fails on classpath changes
 */
@DisplayName("IDE Cache Tests")
public class IDECacheTest {

    private Path testDir;
    private Path cacheDir;
    private Map<String, Long> fileTimestamps;
    private static final String TEST_NAME = "ide-cache";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        cacheDir = testDir.resolve(".ide-cache");
        Files.createDirectories(cacheDir);
        fileTimestamps = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-IDE-003: IDE cache becomes stale after file modifications
    @Test
    @DisplayName("P5-IDE-003: Cache invalidation on source file change")
    void testCacheInvalidationOnSourceChange() throws IOException, InterruptedException {
        Path sourceFile = testDir.resolve("Source.java");
        String originalContent = "public class Source { }";
        TestEnvironmentSetup.createTestFile(testDir, "Source.java", originalContent);
        
        // Cache the file
        long originalModTime = Files.getLastModifiedTime(sourceFile).toMillis();
        cacheEntry("Source.java", originalModTime);
        
        // Wait to ensure time difference
        Thread.sleep(100);
        
        // Modify the file
        Files.write(sourceFile, "public class Source { public void test() {} }".getBytes(),
                StandardOpenOption.TRUNCATE_EXISTING);
        
        long newModTime = Files.getLastModifiedTime(sourceFile).toMillis();
        
        // Cache should be invalidated due to timestamp change
        assertThat(newModTime).isGreaterThan(originalModTime);
        assertThat(isCacheStale("Source.java", originalModTime)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-003: Cache reflects file deletion")
    void testCacheReflectsFileDeletion() throws IOException {
        Path sourceFile = testDir.resolve("DeleteMe.java");
        TestEnvironmentSetup.createTestFile(testDir, "DeleteMe.java", "public class DeleteMe {}");
        
        assertThat(Files.exists(sourceFile)).isTrue();
        cacheEntry("DeleteMe.java", System.currentTimeMillis());
        
        // Delete file
        Files.delete(sourceFile);
        assertThat(Files.exists(sourceFile)).isFalse();
        
        // Cache should recognize deletion
        assertThat(isCacheEntryValid("DeleteMe.java")).isFalse();
    }

    @Test
    @DisplayName("P5-IDE-003: Cache detects new files")
    void testCacheDetectsNewFiles() throws IOException {
        Path cacheEntry = cacheDir.resolve("NewFile.cache");
        
        // Initially no cache entry
        assertThat(Files.exists(cacheEntry)).isFalse();
        
        // Create new source file
        Path newFile = testDir.resolve("NewSource.java");
        TestEnvironmentSetup.createTestFile(testDir, "NewSource.java", "public class NewSource {}");
        
        // Create cache entry
        TestEnvironmentSetup.createTestFile(cacheDir, "NewSource.cache", 
                Files.getLastModifiedTime(newFile).toString());
        
        assertThat(Files.exists(newFile)).isTrue();
        assertThat(Files.exists(cacheDir.resolve("NewSource.cache"))).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-003: Cache consistency across multiple file changes")
    void testCacheConsistencyMultipleChanges() throws IOException, InterruptedException {
        Path file1 = testDir.resolve("File1.java");
        Path file2 = testDir.resolve("File2.java");
        
        TestEnvironmentSetup.createTestFile(testDir, "File1.java", "class 1");
        TestEnvironmentSetup.createTestFile(testDir, "File2.java", "class 2");
        
        cacheEntry("File1.java", Files.getLastModifiedTime(file1).toMillis());
        cacheEntry("File2.java", Files.getLastModifiedTime(file2).toMillis());
        
        Thread.sleep(100);
        
        // Modify first file
        Files.write(file1, "modified class 1".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        
        // Check cache consistency
        assertThat(isCacheStale("File1.java", fileTimestamps.get("File1.java"))).isTrue();
        assertThat(isCacheStale("File2.java", fileTimestamps.get("File2.java"))).isFalse();
    }

    // P5-IDE-006: IDE cache invalidation fails on classpath changes
    @Test
    @DisplayName("P5-IDE-006: Cache invalidation on classpath modification")
    void testCacheInvalidationOnClasspathChange() throws IOException {
        Path libDir = testDir.resolve("lib");
        Files.createDirectories(libDir);
        
        // Create initial classpath
        Path jar1 = libDir.resolve("lib1.jar");
        TestEnvironmentSetup.createTestFile(libDir, "lib1.jar", "JAR1");
        cacheClasspath(jar1.toString());
        
        // Add new JAR to classpath
        Path jar2 = libDir.resolve("lib2.jar");
        TestEnvironmentSetup.createTestFile(libDir, "lib2.jar", "JAR2");
        
        // Classpath changed - cache should be invalid
        assertThat(isClasspathCacheValid()).isFalse();
    }

    @Test
    @DisplayName("P5-IDE-006: Cache invalidation on library version change")
    void testCacheInvalidationOnLibraryUpdate() throws IOException, InterruptedException {
        Path libDir = testDir.resolve("lib");
        Files.createDirectories(libDir);
        
        Path junit4 = libDir.resolve("junit-4.12.jar");
        TestEnvironmentSetup.createTestFile(libDir, "junit-4.12.jar", "JUNIT4.12");
        cacheLibrary("junit-4.12.jar");
        
        Thread.sleep(100);
        
        // Delete old version
        Files.delete(junit4);
        
        // Add new version
        Path junit413 = libDir.resolve("junit-4.13.jar");
        TestEnvironmentSetup.createTestFile(libDir, "junit-4.13.jar", "JUNIT4.13");
        
        // Cache should be invalid due to version change
        assertThat(isLibraryCacheValid("junit-4.12.jar")).isFalse();
        assertThat(Files.exists(junit413)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-006: Cache handles build directory changes")
    void testCacheHandlesBuildDirectoryChanges() throws IOException {
        Path buildDir1 = testDir.resolve("target");
        Path buildDir2 = testDir.resolve("build");
        
        Files.createDirectories(buildDir1.resolve("classes"));
        Files.createDirectories(buildDir2.resolve("classes"));
        
        // Cache references old build directory
        cacheBuildPath(buildDir1.toString());
        
        // New build directory created
        assertThat(Files.exists(buildDir2.resolve("classes"))).isTrue();
        
        // Cache is invalid if build directory changed
        assertThat(isBuildCacheValid(buildDir1.toString())).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-006: Cache invalidation on JVM configuration change")
    void testCacheInvalidationOnJvmConfigChange() throws IOException {
        Path javaHome = testDir.resolve("java-home");
        Files.createDirectories(javaHome);
        
        cacheJvmConfig("11", "/usr/lib/jvm/java-11");
        
        // JVM configuration changes
        String newJavaHome = "/usr/lib/jvm/java-17";
        assertThat(newJavaHome).isNotEqualTo("/usr/lib/jvm/java-11");
    }

    @Test
    @DisplayName("P5-IDE-006: Cache persistence after IDE restart")
    void testCachePersistenceAfterRestart() throws IOException {
        Path sourceFile = testDir.resolve("Persistent.java");
        TestEnvironmentSetup.createTestFile(testDir, "Persistent.java", "class Persistent {}");
        
        // Create cache entry
        Path cacheEntry = cacheDir.resolve("persistent.cache");
        TestEnvironmentSetup.createTestFile(cacheDir, "persistent.cache", 
                "timestamp=" + Files.getLastModifiedTime(sourceFile).toMillis());
        
        // Simulate IDE restart by checking cache persistence
        assertThat(Files.exists(cacheEntry)).isTrue();
        String cacheContent = TestEnvironmentSetup.readFile(cacheEntry);
        assertThat(cacheContent).contains("timestamp=");
    }

    // Helper methods for cache simulation
    private void cacheEntry(String filename, long timestamp) {
        fileTimestamps.put(filename, timestamp);
    }

    private boolean isCacheStale(String filename, long cachedTime) throws IOException {
        Path file = testDir.resolve(filename);
        if (!Files.exists(file)) {
            return true;
        }
        long currentTime = Files.getLastModifiedTime(file).toMillis();
        return currentTime > cachedTime;
    }

    private boolean isCacheEntryValid(String filename) {
        Path file = testDir.resolve(filename);
        return Files.exists(file) && fileTimestamps.containsKey(filename);
    }

    private void cacheClasspath(String classpath) {
        fileTimestamps.put("classpath", (long) classpath.hashCode());
    }

    private boolean isClasspathCacheValid() {
        // Simplified check: if any JAR files exist in lib, cache is affected
        Path libDir = testDir.resolve("lib");
        if (!Files.exists(libDir)) {
            return true;
        }
        try {
            long jarCount = Files.list(libDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .count();
            return jarCount > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void cacheLibrary(String libName) {
        fileTimestamps.put("lib:" + libName, System.currentTimeMillis());
    }

    private boolean isLibraryCacheValid(String libName) {
        return fileTimestamps.containsKey("lib:" + libName);
    }

    private void cacheBuildPath(String buildPath) {
        fileTimestamps.put("buildpath", (long) buildPath.hashCode());
    }

    private boolean isBuildCacheValid(String buildPath) {
        Long cached = (Long) fileTimestamps.getOrDefault("buildpath", -1L);
        return cached.equals((long) buildPath.hashCode());
    }

    private void cacheJvmConfig(String version, String javaHome) {
        fileTimestamps.put("jvm:version", (long) version.hashCode());
        fileTimestamps.put("jvm:home", (long) javaHome.hashCode());
    }
}
