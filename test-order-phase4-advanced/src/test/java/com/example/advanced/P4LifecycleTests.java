package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;

/**
 * Pattern 4: Test Lifecycle Callbacks
 * Testing @BeforeAll, @BeforeEach, @AfterEach, @AfterAll
 * with complex state and lifecycle dependencies
 */
class P4LifecycleTests {

    private static int globalCounter = 0;
    private int instanceCounter = 0;

    @BeforeAll
    static void setupClass() {
        globalCounter = 0;
        System.out.println("BeforeAll: Setup class");
    }

    @BeforeEach
    void setupMethod() {
        instanceCounter++;
        globalCounter++;
        System.out.println("BeforeEach: Setup method");
    }

    @Test
    void lifecycleTest1() {
        assert instanceCounter > 0;
        assert globalCounter > 0;
    }

    @Test
    void lifecycleTest2() {
        assert instanceCounter > 0;
    }

    @Test
    void lifecycleTest3() {
        assert globalCounter > 0;
    }

    @AfterEach
    void teardownMethod() {
        System.out.println("AfterEach: Cleanup method");
    }

    @AfterAll
    static void teardownClass() {
        System.out.println("AfterAll: Cleanup class");
    }

    // Total: 3 tests with full lifecycle
}
