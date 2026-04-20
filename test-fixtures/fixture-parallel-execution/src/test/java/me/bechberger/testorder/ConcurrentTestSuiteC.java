package me.bechberger.testorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that run concurrently with other test classes.
 * This suite has varied test durations to generate realistic contention
 * on the shared state file when test-order writes scoring data.
 */
@DisplayName("Concurrent Execution - Suite C")
public class ConcurrentTestSuiteC {

    @Test
    @DisplayName("test_C_001_very_fast")
    void testC001VeryFast() {
        int x = 1 + 1;
        assert x == 2;
    }

    @Test
    @DisplayName("test_C_002_medium")
    @Timeout(2)
    void testC002Medium() {
        long sum = 0;
        for (int i = 0; i < 4000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_C_003_slow")
    @Timeout(3)
    void testC003Slow() {
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_C_004_quick")
    void testC004Quick() {
        assert "test".length() == 4;
    }

    @Test
    @DisplayName("test_C_005_sleep")
    void testC005Sleep() throws Exception {
        Thread.sleep(150);
        assert true;
    }

    @Test
    @DisplayName("test_C_006_interactive")
    void testC006Interactive() throws Exception {
        for (int i = 0; i < 5; i++) {
            Thread.sleep(40);
        }
        assert true;
    }

    @Test
    @DisplayName("test_C_007_burst")
    void testC007Burst() {
        long sum = 0;
        for (int i = 0; i < 2000000; i++) {
            sum += i * i;
        }
        assert sum > 0;
    }
}
