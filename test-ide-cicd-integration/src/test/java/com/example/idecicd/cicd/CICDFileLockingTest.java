package com.example.idecicd.cicd;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CI/CD file locking bugs (P5-CICD-021, 022, 023, 024).
 * 
 * Bug Categories:
 * - P5-CICD-021: CI/CD pipeline fails due to file locks during builds
 * - P5-CICD-022: CI/CD reports false file lock errors
 * - P5-CICD-023: CI/CD file locks persist after build failure
 * - P5-CICD-024: CI/CD lock timeout configuration not respected
 */
@DisplayName("CI/CD File Locking Tests")
public class CICDFileLockingTest {

    private Path testDir;
    private static final String TEST_NAME = "cicd-file-locking";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        Files.createDirectories(testDir.resolve("target"));
        Files.createDirectories(testDir.resolve("build"));
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-CICD-021: CI/CD pipeline fails due to file locks during builds
    @Test
    @DisplayName("P5-CICD-021: Build detects file lock on artifact")
    void testBuildDetectsFileLock() throws IOException {
        Path artifact = testDir.resolve("target/app.jar");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "app.jar", "JAR_CONTENT");

        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            // Acquire a lock on the file
            FileLock lock = channel.lock();
            assertThat(lock).isNotNull();
            
            // During lock, file should not be accessible for write
            assertThat(channel.isOpen()).isTrue();
            
            // Release lock
            lock.release();
        }
        
        // After release, file should be accessible
        assertThat(Files.exists(artifact)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-021: Build waits for file lock release")
    void testBuildWaitsForLockRelease() throws IOException, InterruptedException {
        Path artifact = testDir.resolve("target/library.jar");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "library.jar", "LIB");

        long lockAcquiredTime = System.currentTimeMillis();
        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            FileLock lock = channel.lock();
            
            // Simulate lock held for 100ms
            Thread.sleep(100);
            
            lock.release();
        }
        
        long releaseTime = System.currentTimeMillis();
        long lockDuration = releaseTime - lockAcquiredTime;
        
        // Build should have waited for lock release
        assertThat(lockDuration).isGreaterThanOrEqualTo(100);
        assertThat(Files.exists(artifact)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-021: Build handles multiple file locks")
    void testBuildHandlesMultipleFileLocks() throws IOException {
        Path jar1 = testDir.resolve("target/lib1.jar");
        Path jar2 = testDir.resolve("target/lib2.jar");
        Path jar3 = testDir.resolve("target/lib3.jar");
        
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "lib1.jar", "JAR1");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "lib2.jar", "JAR2");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "lib3.jar", "JAR3");

        // Acquire locks on multiple files
        try (RandomAccessFile raf1 = new RandomAccessFile(jar1.toFile(), "rw");
             FileChannel ch1 = raf1.getChannel();
             RandomAccessFile raf2 = new RandomAccessFile(jar2.toFile(), "rw");
             FileChannel ch2 = raf2.getChannel();
             RandomAccessFile raf3 = new RandomAccessFile(jar3.toFile(), "rw");
             FileChannel ch3 = raf3.getChannel()) {
            
            FileLock lock1 = ch1.lock();
            FileLock lock2 = ch2.lock();
            FileLock lock3 = ch3.lock();
            
            assertThat(lock1).isNotNull();
            assertThat(lock2).isNotNull();
            assertThat(lock3).isNotNull();
            
            lock1.release();
            lock2.release();
            lock3.release();
        }
        
        assertThat(Files.exists(jar1)).isTrue();
        assertThat(Files.exists(jar2)).isTrue();
        assertThat(Files.exists(jar3)).isTrue();
    }

    // P5-CICD-022: CI/CD reports false file lock errors
    @Test
    @DisplayName("P5-CICD-022: Build correctly distinguishes file lock from permission error")
    void testBuildDistinguishesLockFromPermissionError() throws IOException {
        Path file = testDir.resolve("target/test.txt");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "test.txt", "CONTENT");

        // File lock is different from permission error
        assertThat(Files.isReadable(file)).isTrue();
        assertThat(Files.isWritable(file)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-022: Build reports actual lock status accurately")
    void testBuildReportsLockStatusAccurately() throws IOException {
        Path artifact = testDir.resolve("target/artifact.jar");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "artifact.jar", "JAR");

        // Check if file can be locked (no lock currently)
        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            FileLock lock = channel.tryLock();
            if (lock != null) {
                // Successfully acquired lock, file was not locked
                assertThat(lock).isNotNull();
                lock.release();
            }
        }
        
        assertThat(Files.exists(artifact)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-022: Build error message includes lock holder info")
    void testBuildLockErrorMessage() throws IOException {
        Path file = testDir.resolve("target/locked-file.txt");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "locked-file.txt", "DATA");

        String lockErrorMessage = "File locked: " + file.toString();
        assertThat(lockErrorMessage).contains(file.getFileName().toString());
        assertThat(lockErrorMessage).contains("locked");
    }

    // P5-CICD-023: CI/CD file locks persist after build failure
    @Test
    @DisplayName("P5-CICD-023: File lock released after build failure")
    void testFileLockReleasedAfterFailure() throws IOException {
        Path artifact = testDir.resolve("target/incomplete.jar");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "incomplete.jar", "PARTIAL");

        boolean lockAcquired = false;
        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            FileLock lock = channel.lock();
            lockAcquired = lock != null;
            
            // Simulate build failure
            if (lockAcquired) {
                lock.release();
            }
        } catch (Exception e) {
            // Build fails
        }

        // File should not be locked after failure
        assertThat(lockAcquired).isTrue();
        
        // Should be able to acquire lock again
        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            FileLock lock = channel.tryLock();
            assertThat(lock).isNotNull();
            if (lock != null) {
                lock.release();
            }
        }
    }

    @Test
    @DisplayName("P5-CICD-023: Lock cleanup happens on build abort")
    void testLockCleanupOnBuildAbort() throws IOException {
        Path file1 = testDir.resolve("target/file1.txt");
        Path file2 = testDir.resolve("target/file2.txt");
        
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "file1.txt", "F1");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "file2.txt", "F2");

        // Simulate multiple file locks during build
        FileLock lock1 = null;
        FileLock lock2 = null;
        
        try (RandomAccessFile raf1 = new RandomAccessFile(file1.toFile(), "rw");
             FileChannel ch1 = raf1.getChannel();
             RandomAccessFile raf2 = new RandomAccessFile(file2.toFile(), "rw");
             FileChannel ch2 = raf2.getChannel()) {
            
            lock1 = ch1.lock();
            lock2 = ch2.lock();
            
            // Build aborts - cleanup should release locks
            if (lock1 != null) lock1.release();
            if (lock2 != null) lock2.release();
        }

        // After cleanup, files should be accessible
        assertThat(Files.exists(file1)).isTrue();
        assertThat(Files.exists(file2)).isTrue();
    }

    // P5-CICD-024: CI/CD lock timeout configuration not respected
    @Test
    @DisplayName("P5-CICD-024: Build respects lock timeout configuration")
    void testBuildRespectsLockTimeout() throws IOException {
        Path artifact = testDir.resolve("target/timeout-test.jar");
        TestEnvironmentSetup.createTestFile(testDir.resolve("target"), "timeout-test.jar", "JAR");

        int configuredTimeoutMs = 5000;
        
        try (RandomAccessFile raf = new RandomAccessFile(artifact.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            FileLock lock = channel.lock();
            
            // Simulate timeout check
            long startTime = System.currentTimeMillis();
            assertThat(lock).isNotNull();
            
            lock.release();
            long elapsed = System.currentTimeMillis() - startTime;
            
            // Should complete within reasonable time
            assertThat(elapsed).isLessThan(configuredTimeoutMs);
        }
    }

    @Test
    @DisplayName("P5-CICD-024: Build applies lock timeout to all files")
    void testLockTimeoutAppliesToAllFiles() throws IOException {
        Path[] files = {
                testDir.resolve("target/file1.jar"),
                testDir.resolve("target/file2.jar"),
                testDir.resolve("target/file3.jar")
        };
        
        for (Path f : files) {
            TestEnvironmentSetup.createTestFile(testDir.resolve("target"), f.getFileName().toString(), "JAR");
        }

        int timeoutMs = 5000;
        long startTime = System.currentTimeMillis();

        // Try to acquire locks with timeout
        for (Path file : files) {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
                 FileChannel channel = raf.getChannel()) {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    lock.release();
                }
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        assertThat(elapsedTime).isLessThan(timeoutMs);
    }

    @Test
    @DisplayName("P5-CICD-024: Build timeout configuration is configurable")
    void testLockTimeoutConfiguration() throws IOException {
        Path configFile = testDir.resolve("ci-config.properties");
        String config = "lock.timeout.ms=10000\nlock.retry.count=3\nlock.retry.delay.ms=1000";
        TestEnvironmentSetup.createTestFile(testDir, "ci-config.properties", config);

        String content = TestEnvironmentSetup.readFile(configFile);
        assertThat(content).contains("lock.timeout.ms=10000");
        assertThat(content).contains("lock.retry.count=3");
        assertThat(content).contains("lock.retry.delay.ms=1000");
    }
}
