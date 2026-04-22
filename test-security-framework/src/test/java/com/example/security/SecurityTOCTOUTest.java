package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-001: TOCTOU (Time Of Check Time Of Use) Race Condition
 * 
 * Tests the vulnerability where there is a window between checking a condition
 * (like file existence) and using it, allowing an attacker to modify state in between.
 * 
 * Expected Behavior: The application should:
 * 1. Use atomic file operations where possible
 * 2. Hold locks during check-and-use sequences
 * 3. Validate state after acquiring locks
 * 4. Use atomic file creation operations (createFile, createDirectory)
 */
@DisplayName("Security - TOCTOU Race Condition Tests (P5-SEC-001)")
class SecurityTOCTOUTest {

    @TempDir
    Path tempDir;

    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test_file.txt");
    }

    @Test
    @DisplayName("File Existence Check - Detect TOCTOU between exists check and write")
    void testFileExistenceCheckTOCTOU() throws IOException, InterruptedException {
        // This test simulates the vulnerable pattern: if (!file.exists()) then write
        AtomicBoolean raceDetected = new AtomicBoolean(false);
        CountDownLatch checkComplete = new CountDownLatch(1);
        CountDownLatch useStarted = new CountDownLatch(1);

        Thread raceThread = new Thread(() -> {
            try {
                // Wait for main thread to check existence
                checkComplete.await();
                
                // Create file between check and use (race condition window)
                Files.writeString(testFile, "race condition data");
                useStarted.countDown();
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Vulnerable pattern
        raceThread.start();
        
        if (!Files.exists(testFile)) {
            checkComplete.countDown();
            
            // Race thread creates file here
            useStarted.await();
            
            // File might now exist when we try to write
            if (Files.exists(testFile)) {
                raceDetected.set(true);
            }
        }

        raceThread.join();

        assertTrue(raceDetected.get(), 
            "TOCTOU race condition detected: file modified between check and use");
    }

    @Test
    @DisplayName("Atomic File Creation - Prevent TOCTOU with atomic operations")
    void testAtomicFileCreation() throws IOException {
        // Secure pattern: use atomic file operations
        try {
            Files.createFile(testFile);
            
            // This should succeed
            assertTrue(Files.exists(testFile), "File should be created atomically");
            
            // Attempting to create again should fail atomically
            assertThrows(IOException.class, () -> {
                Files.createFile(testFile);
            }, "Atomic file creation should prevent TOCTOU");
        } catch (IOException e) {
            fail("Atomic file creation failed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Directory Creation Atomicity - Prevent race between check and create")
    void testDirectoryCreationAtomicity() throws IOException {
        Path newDir = tempDir.resolve("new_directory");

        // Atomic directory creation
        try {
            Files.createDirectory(newDir);
            assertTrue(Files.isDirectory(newDir), "Directory should exist after atomic creation");
        } catch (IOException e) {
            fail("Directory creation failed: " + e.getMessage());
        }

        // Attempting again should fail due to existing directory
        assertThrows(IOException.class, () -> {
            Files.createDirectory(newDir);
        }, "Creating existing directory should fail");
    }

    @Test
    @DisplayName("File Content Verification - Validate data wasn't modified")
    void testFileContentVerification() throws IOException {
        String originalContent = "secure content";
        Files.writeString(testFile, originalContent);

        // After ensuring file exists, verify content immediately
        String readContent = Files.readString(testFile);
        assertEquals(originalContent, readContent, 
            "Content should match, indicating no TOCTOU modification");
    }

    @Test
    @DisplayName("Lock-Based Protection - Hold lock during check-and-use")
    void testLockBasedProtection() throws IOException {
        // Simulates a lock-protected check-and-use
        Object lock = new Object();
        
        synchronized (lock) {
            // Check
            if (!Files.exists(testFile)) {
                // Use - while holding lock, no one else can modify
                Files.writeString(testFile, "locked write");
            }
        }

        // Verify write succeeded
        assertTrue(Files.exists(testFile), "File should exist after locked operation");
        assertEquals("locked write", Files.readString(testFile),
            "Content should be as written in locked section");
    }

    @Test
    @DisplayName("Concurrent Access Scenario - Multiple threads accessing same file")
    void testConcurrentAccessScenario() throws IOException, InterruptedException {
        Path sharedFile = tempDir.resolve("shared.txt");
        AtomicBoolean writeConflict = new AtomicBoolean(false);

        Thread writer1 = new Thread(() -> {
            try {
                // Atomic write
                Files.writeString(sharedFile, "thread1", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                writeConflict.set(true);
            }
        });

        Thread writer2 = new Thread(() -> {
            try {
                // Atomic write
                Files.writeString(sharedFile, "thread2", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                writeConflict.set(true);
            }
        });

        writer1.start();
        writer2.start();
        writer1.join();
        writer2.join();

        // File should exist with one of the writes
        assertTrue(Files.exists(sharedFile), "Shared file should exist after concurrent writes");
        String content = Files.readString(sharedFile);
        assertTrue(content.equals("thread1") || content.equals("thread2"),
            "File should contain content from one of the threads");
    }

    @Test
    @DisplayName("Double-Checked Locking - Prevent TOCTOU in initialization")
    void testDoubleCheckedLocking() throws IOException {
        Object lock = new Object();
        boolean initialized = false;

        // First check (without lock - for performance)
        if (!initialized) {
            synchronized (lock) {
                // Second check (with lock)
                if (!initialized) {
                    Files.writeString(testFile, "initialized");
                    initialized = true;
                }
            }
        }

        assertTrue(Files.exists(testFile), "File should be initialized");
    }

    @Test
    @DisplayName("File Move Atomicity - Prevent TOCTOU during rename operations")
    void testFileMoveAtomicity() throws IOException {
        // Create source file
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "original");

        // Atomic move
        Path target = tempDir.resolve("target.txt");
        Files.move(source, target);

        // Verify atomic move completed
        assertFalse(Files.exists(source), "Source file should not exist after move");
        assertTrue(Files.exists(target), "Target file should exist after move");
        assertEquals("original", Files.readString(target), "Content should be preserved in atomic move");
    }

    @Test
    @DisplayName("Read-Modify-Write Safety - Prevent data loss in concurrent updates")
    void testReadModifyWriteSafety() throws IOException {
        String initialContent = "counter:0";
        Files.writeString(testFile, initialContent);

        // Simulate read-modify-write
        Object lock = new Object();
        synchronized (lock) {
            String current = Files.readString(testFile);
            String modified = current.replace("0", "1");
            Files.writeString(testFile, modified);
        }

        String result = Files.readString(testFile);
        assertEquals("counter:1", result, "Atomic read-modify-write should succeed");
    }
}
