package com.example.advanced;

import org.junit.jupiter.api.Test;

/**
 * Pattern 9: Test Dependency Annotations
 * Custom tests that demonstrate dependency-like behavior
 */
class P9TestDependencyTests {

    // Base test - should run first
    @Test
    void baseSetupTest() {
        assert true;
    }

    // Dependent test - should run after base
    @Test
    void dependentTest1() {
        assert true;
    }

    // Another dependent test
    @Test
    void dependentTest2() {
        assert true;
    }

    // Chained dependency
    @Test
    void chainedDependentTest() {
        assert true;
    }

    // Total: 4 tests with dependency-like ordering
}
