package com.example;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulated flaky test. A counter file at target/flaky-counter increments
 * on each test invocation (across retries within the same JVM). The test
 * fails when the counter is &lt; FAIL_UNTIL, passes thereafter.
 *
 * - FAIL_UNTIL=1 → fails attempt 0, passes attempt 1 (retry recovers)
 * - FAIL_UNTIL=99 → always fails (no recovery; tests quarantine path)
 *
 * Controlled by system property "sample.flaky.failUntil" (default 0 = always pass).
 */
class FlakyServiceTest {
    private static final Path COUNTER = Path.of("target/flaky-counter");

    @Test
    void flaky() throws Exception {
        int failUntil = Integer.parseInt(System.getProperty("sample.flaky.failUntil", "0"));
        Files.createDirectories(COUNTER.getParent());
        int attempt = readCounter();
        writeCounter(attempt + 1);
        if (attempt < failUntil) {
            throw new AssertionError("flaky failure on attempt " + attempt);
        }
        assertTrue(true);
    }

    private static int readCounter() {
        try {
            return Integer.parseInt(Files.readString(COUNTER).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeCounter(int v) throws Exception {
        Files.writeString(COUNTER, Integer.toString(v));
    }
}
