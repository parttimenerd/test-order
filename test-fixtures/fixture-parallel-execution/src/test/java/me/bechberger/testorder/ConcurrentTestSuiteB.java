package me.bechberger.testorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that run concurrently with other test classes to exercise state file locking.
 * These tests deliberately vary in duration to ensure test-order scoring captures
 * accurate timing data even when multiple threads write metrics simultaneously.
 */
@DisplayName("Concurrent Execution - Suite B")
public class ConcurrentTestSuiteB {

    @Test
    @DisplayName("test_B_001_fast")
    void testB001Fast() {
        assert true;
    }

    @Test
    @DisplayName("test_B_002_medium")
    @Timeout(1)
    void testB002Medium() {
        long sum = 0;
        for (int i = 0; i < 3000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_B_003_slow")
    @Timeout(3)
    void testB003Slow() {
        long sum = 0;
        for (int i = 0; i < 8000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_B_004_quick")
    void testB004Quick() {
        assert 2 + 2 == 4;
    }

    @Test
    @DisplayName("test_B_005_blocking")
    void testB005Blocking() throws Exception {
        // Simulate blocking I/O to create interleaving with other test threads
        Thread.sleep(100);
        assert true;
    }

    @Test
    @DisplayName("test_B_006_io_heavy")
    void testB006IOHeavy() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(20);
        }
        assert true;
    }
}
