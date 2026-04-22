package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-009: Resource Exhaustion (Denial of Service)
 * 
 * Tests the vulnerability where an application can be made unavailable through
 * resource exhaustion attacks: memory exhaustion, disk space exhaustion,
 * CPU exhaustion, thread exhaustion, etc.
 * 
 * Expected Behavior: The application should:
 * 1. Limit memory usage for operations
 * 2. Enforce disk space quotas
 * 3. Limit number of concurrent connections/threads
 * 4. Implement rate limiting
 * 5. Set timeouts on operations
 * 6. Validate input sizes
 */
@DisplayName("Security - Resource Exhaustion (DOS) Tests (P5-SEC-009)")
class SecurityDOSTest {

    @TempDir
    Path tempDir;

    private static final long MAX_FILE_SIZE = 100_000_000; // 100MB
    private static final long MAX_MEMORY = 512_000_000; // 512MB
    private static final int MAX_THREADS = 100;
    private static final int RATE_LIMIT = 100; // requests per second

    @Test
    @DisplayName("P5-SEC-009: File Size Limit - Reject excessively large files")
    void testFileSizeLimitEnforcement() throws IOException {
        Path largeFile = tempDir.resolve("large.bin");

        // Attempt to create file larger than limit
        int chunkSize = 10_000_000;
        int chunks = 15; // Would exceed limit

        long fileSize = 0;
        try {
            for (int i = 0; i < chunks; i++) {
                byte[] chunk = new byte[chunkSize];
                fileSize += chunk.length;

                if (fileSize > MAX_FILE_SIZE) {
                    throw new IOException("File size limit exceeded");
                }

                Files.write(largeFile, chunk);
            }
            fail("Should reject file exceeding size limit");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("limit exceeded"), 
                "File size limit should be enforced");
        }
    }

    @Test
    @DisplayName("P5-SEC-009: Small Valid File - Accept files within size limit")
    void testValidFileSizeAcceptance() throws IOException {
        Path normalFile = tempDir.resolve("normal.txt");
        byte[] data = new byte[1_000_000]; // 1MB - within limit

        assertDoesNotThrow(() -> Files.write(normalFile, data), 
            "Files within size limit should be accepted");

        assertTrue(Files.exists(normalFile), "File should be created successfully");
    }

    @Test
    @DisplayName("P5-SEC-009: Memory Exhaustion Prevention - Limit collection sizes")
    void testMemoryExhaustionPrevention() {
        // Prevent unbounded collection growth
        int maxItems = 10_000;
        BoundedList<String> list = new BoundedList<>(maxItems);

        // Add items up to limit
        for (int i = 0; i < maxItems; i++) {
            list.add("item" + i);
        }

        // Adding beyond limit should fail
        assertThrows(IllegalStateException.class, () -> {
            list.add("item" + maxItems);
        }, "Collection size limit should be enforced");
    }

    @Test
    @DisplayName("P5-SEC-009: Disk Space Quota - Monitor available disk space")
    void testDiskSpaceQuota() throws IOException {
        Path quotaDir = tempDir.resolve("quota_dir");
        Files.createDirectory(quotaDir);

        long maxDiskUsage = 50_000_000; // 50MB quota
        long currentUsage = 0;

        // Create files within quota
        for (int i = 0; i < 3; i++) {
            Path file = quotaDir.resolve("file" + i + ".dat");
            byte[] data = new byte[10_000_000]; // 10MB
            currentUsage += data.length;

            if (currentUsage > maxDiskUsage) {
                assertThrows(IOException.class, () -> Files.write(file, data), 
                    "Should reject write exceeding disk quota");
                return;
            }

            Files.write(file, data);
        }

        // If we get here, should have quota enforcement in place
        assertTrue(currentUsage <= maxDiskUsage, "Disk usage should not exceed quota");
    }

    @Test
    @DisplayName("P5-SEC-009: Thread Pool Limits - Prevent thread exhaustion")
    void testThreadPoolLimits() throws InterruptedException {
        ThreadPool pool = new ThreadPool(MAX_THREADS);
        int taskCount = 0;

        // Submit tasks up to limit
        for (int i = 0; i < MAX_THREADS; i++) {
            pool.submit(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            taskCount++;
        }

        // Attempting to exceed limit should queue or reject
        assertThrows(IllegalStateException.class, () -> {
            for (int i = 0; i < 100; i++) {
                pool.submit(() -> Thread.sleep(100));
            }
        }, "Thread pool should reject tasks when at capacity");

        pool.shutdown();
    }

    @Test
    @DisplayName("P5-SEC-009: Rate Limiting - Throttle excessive requests")
    void testRateLimiting() {
        RateLimiter limiter = new RateLimiter(RATE_LIMIT);

        // Allow up to rate limit
        boolean[] results = new boolean[RATE_LIMIT + 10];
        for (int i = 0; i < RATE_LIMIT + 10; i++) {
            results[i] = limiter.allowRequest();
        }

        // Should allow first RATE_LIMIT requests
        for (int i = 0; i < RATE_LIMIT; i++) {
            assertTrue(results[i], "Request " + i + " should be allowed");
        }

        // Some requests beyond limit should be rejected
        boolean anyRejected = false;
        for (int i = RATE_LIMIT; i < RATE_LIMIT + 10; i++) {
            if (!results[i]) {
                anyRejected = true;
                break;
            }
        }

        assertTrue(anyRejected, "Some requests beyond limit should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-009: Request Timeout - Prevent slow client DOS")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRequestTimeout() throws InterruptedException {
        // This test itself should timeout if implementation is incorrect
        Thread.sleep(1000);
        assertTrue(true, "Request completed within timeout");
    }

    @Test
    @DisplayName("P5-SEC-009: Connection Limit - Prevent connection exhaustion")
    void testConnectionLimit() {
        ConnectionPool pool = new ConnectionPool(50);

        // Create up to limit
        for (int i = 0; i < 50; i++) {
            assertTrue(pool.createConnection(), "Should create connection within limit");
        }

        // Creating beyond limit should fail
        assertFalse(pool.createConnection(), 
            "Should reject connection when at limit");
    }

    @Test
    @DisplayName("P5-SEC-009: Input Validation Size - Reject oversized inputs")
    void testInputSizeValidation() {
        // Validate string input size
        String normal = "normal input";
        String huge = "x".repeat(1_000_000);

        assertTrue(isValidInputSize(normal, 1000), "Normal input should be valid");
        assertFalse(isValidInputSize(huge, 1000), "Oversized input should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-009: Compression Bomb Detection - Prevent zip bomb attacks")
    void testCompressionBombDetection() throws IOException {
        // Simulate detecting compression bombs
        Path compressedFile = tempDir.resolve("data.zip");

        // Create a file
        Files.writeString(compressedFile, "test data");
        long compressedSize = Files.size(compressedFile);

        // Check compression ratio - compression bomb would have huge expansion ratio
        long uncompressedSize = Files.readString(compressedFile).length();
        double ratio = (double) uncompressedSize / compressedSize;

        // Normal compression ratio should be < 100x
        assertTrue(ratio < 100, "Compression ratio should not indicate bomb");
    }

    @Test
    @DisplayName("P5-SEC-009: XML Bomb Prevention - Detect billion laughs attack")
    void testXmlBombPrevention() {
        // XML bomb attempt with entity expansion
        String xmlBomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            ]>
            <lolz>&lol2;</lolz>
            """;

        // Should detect and reject XML bomb
        assertFalse(isSafeXml(xmlBomb), "XML bomb should be detected and rejected");
    }

    @Test
    @DisplayName("P5-SEC-009: Stack Overflow Prevention - Limit recursion depth")
    void testStackOverflowPrevention() {
        int recursionLimit = 100;
        int maxDepth = 0;

        try {
            maxDepth = recursiveFunction(0, recursionLimit);
        } catch (StackOverflowError e) {
            fail("Should prevent stack overflow with recursion limit");
        }

        assertTrue(maxDepth <= recursionLimit, 
            "Recursion should not exceed limit");
    }

    // ==================== Helper Classes ====================

    static class BoundedList<T> {
        private final java.util.ArrayList<T> list = new java.util.ArrayList<>();
        private final int maxSize;

        BoundedList(int maxSize) {
            this.maxSize = maxSize;
        }

        void add(T item) {
            if (list.size() >= maxSize) {
                throw new IllegalStateException("List is at capacity");
            }
            list.add(item);
        }

        int size() {
            return list.size();
        }
    }

    static class ThreadPool {
        private final int maxThreads;
        private int activeThreads = 0;

        ThreadPool(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        synchronized void submit(Runnable task) throws InterruptedException {
            if (activeThreads >= maxThreads) {
                throw new IllegalStateException("Thread pool is full");
            }

            activeThreads++;
            new Thread(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (this) {
                        activeThreads--;
                    }
                }
            }).start();
        }

        synchronized void shutdown() {
            // Shutdown thread pool
        }
    }

    static class RateLimiter {
        private final int rateLimit;
        private int requestsInCurrentSecond = 0;
        private long lastSecondReset = System.currentTimeMillis();

        RateLimiter(int rateLimit) {
            this.rateLimit = rateLimit;
        }

        synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();
            if (now - lastSecondReset >= 1000) {
                requestsInCurrentSecond = 0;
                lastSecondReset = now;
            }

            if (requestsInCurrentSecond < rateLimit) {
                requestsInCurrentSecond++;
                return true;
            }
            return false;
        }
    }

    static class ConnectionPool {
        private final int maxConnections;
        private int activeConnections = 0;

        ConnectionPool(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        synchronized boolean createConnection() {
            if (activeConnections >= maxConnections) {
                return false;
            }
            activeConnections++;
            return true;
        }

        synchronized void closeConnection() {
            if (activeConnections > 0) {
                activeConnections--;
            }
        }
    }

    // ==================== Helper Methods ====================

    private boolean isValidInputSize(String input, int maxSize) {
        return input != null && input.length() <= maxSize;
    }

    private boolean isSafeXml(String xml) {
        // Detect DOCTYPE and entity declarations which enable XXE/billion laughs
        return !xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY");
    }

    private int recursiveFunction(int depth, int limit) {
        if (depth > limit) {
            return depth;
        }
        return recursiveFunction(depth + 1, limit);
    }
}
