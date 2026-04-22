package com.example;

import org.junit.jupiter.api.*;
import java.util.concurrent.TimeUnit;

/**
 * Tests for @Timeout handling.
 * Tests edge cases with timeout, slow tests, and timeout interaction with test-order.
 */
@DisplayName("Timeout Handling Tests")
public class TimeoutHandlingTests {

    @Test
    @DisplayName("Test 1: Quick test")
    @Timeout(2)
    public void test01_quickTest() {
        assert true;
    }

    @Test
    @DisplayName("Test 2: Moderate duration test")
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    public void test02_moderateTest() throws InterruptedException {
        Thread.sleep(100);
        assert true;
    }

    @Test
    @DisplayName("Test 3: Test near timeout boundary")
    @Timeout(value = 300, unit = TimeUnit.MILLISECONDS)
    public void test03_nearBoundary() throws InterruptedException {
        Thread.sleep(200);
        assert true;
    }

    @Test
    @DisplayName("Test 4: Another quick test")
    @Timeout(1)
    public void test04_anotherQuick() {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        assert sum > 0;
    }

    @Test
    @DisplayName("Test 5: Test with computation")
    @Timeout(2)
    public void test05_computation() {
        long result = 0;
        for (int i = 0; i < 1000000; i++) {
            result += i;
        }
        assert result > 0;
    }

    @Test
    @DisplayName("Test 6: Test with short timeout")
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    public void test06_shortTimeout() throws InterruptedException {
        Thread.sleep(10);
        assert true;
    }

    @Test
    @DisplayName("Test 7: Test with generous timeout")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void test07_generousTimeout() throws InterruptedException {
        Thread.sleep(100);
        assert true;
    }

    @Test
    @DisplayName("Test 8: Nested timeout test")
    @Timeout(1)
    public void test08_nestedTimeout() {
        try {
            Thread.sleep(50);
            assert true;
        } catch (InterruptedException e) {
            assert false : "Should not be interrupted";
        }
    }

    @Test
    @DisplayName("Test 9: Loop with timeout")
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    public void test09_loopTimeout() {
        for (int i = 0; i < 1000; i++) {
            assert i >= 0;
        }
    }

    @Test
    @DisplayName("Test 10: Final timeout test")
    @Timeout(2)
    public void test10_finalTimeoutTest() {
        assert true;
    }
}
