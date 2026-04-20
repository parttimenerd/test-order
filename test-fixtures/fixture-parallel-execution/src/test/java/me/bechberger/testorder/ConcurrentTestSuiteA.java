package me.bechberger.testorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that run concurrently with other test classes to exercise state file locking.
 * When multiple threads try to write test metadata simultaneously, test-order must
 * handle concurrent state updates without data loss or corruption.
 */
@DisplayName("Concurrent Execution - Suite A")
public class ConcurrentTestSuiteA {

    @Test
    @DisplayName("test_A_001_quick")
    @Timeout(1)
    void testA001Quick() {
        assert true;
    }

    @Test
    @DisplayName("test_A_002_medium")
    @Timeout(1)
    void testA002Medium() {
        // Simulate some work
        long sum = 0;
        for (int i = 0; i < 1000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_A_003_slow")
    @Timeout(2)
    void testA003Slow() {
        // Simulate longer work
        long sum = 0;
        for (int i = 0; i < 5000000; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("test_A_004_io")
    void testA004IO() throws Exception {
        // Simulate I/O which may trigger context switches
        Thread.sleep(50);
        assert true;
    }

    @Test
    @DisplayName("test_A_005_final")
    void testA005Final() {
        assert 1 == 1;
    }
}
