package com.example;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for edge cases with test instance lifecycle and method execution order.
 * Tests interaction between different lifecycle hooks and parameterized/repeated tests.
 */
@DisplayName("Complex Lifecycle Interaction Tests")
public class ComplexLifecycleInteractionTests {

    private int methodCallCount = 0;

    @BeforeEach
    void setup() {
        methodCallCount = 0;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("Parameterized test with lifecycle")
    void testParameterizedWithLifecycle(int value) {
        methodCallCount++;
        assert value > 0;
        assert methodCallCount <= 2;
    }

    @Test
    @DisplayName("Regular test after parameterized")
    void testAfterParameterized() {
        // methodCallCount should be reset by @BeforeEach
        assert methodCallCount == 0;
    }

    @RepeatedTest(2)
    @DisplayName("Repeated test without parameters")
    void testRepeatedNoParams(RepetitionInfo info) {
        assert info.getCurrentRepetition() > 0;
    }

    @Test
    @DisplayName("Test after repeated")
    void testAfterRepeated() {
        // Should have fresh state
        assert methodCallCount == 0;
    }
}
