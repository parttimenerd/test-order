package com.example;

import org.junit.jupiter.api.*;

/**
 * Tests for test-order cache consistency with different JUnit 5 features.
 * Tests edge cases where test-order plugin may have caching issues.
 */
@DisplayName("Test Order Cache and Discovery Consistency")
public class TestOrderCacheConsistencyTests {

    @Test
    @DisplayName("Test A: Stable test 1")
    public void testA1() {
        assert true;
    }

    @Test
    @Disabled("Temporarily disabled")
    @DisplayName("Test B: Disabled test")
    public void testB1() {
        assert false;
    }

    @Test
    @DisplayName("Test C: After disabled")
    public void testC1() {
        assert true;
    }

    @Nested
    @DisplayName("Nested Level")
    class NestedLevel {
        @Test
        @DisplayName("Nested Test 1")
        void nestedTest1() {
            assert true;
        }

        @Test
        @DisplayName("Nested Test 2")
        void nestedTest2() {
            assert true;
        }
    }

    @Test
    @DisplayName("Test D: After nested")
    public void testD1() {
        assert true;
    }

    @Test
    @DisplayName("Test E: Final test")
    public void testE1() {
        assert true;
    }
}
