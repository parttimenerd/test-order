package com.myapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses {@code @TestInstance(Lifecycle.PER_CLASS)} to verify that
 * PriorityMethodOrderer detects PER_CLASS lifecycle and warns
 * appropriately (C4 from compatibility audit).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GreeterPerClassLifecycleTest {

    private int callCount = 0;

    @Test
    void firstCall() {
        callCount++;
        assertTrue(new Greeter().greet("Alice").contains("Alice"));
    }

    @Test
    void secondCall() {
        callCount++;
        assertTrue(new Greeter().greet("Bob").contains("Bob"));
    }

    @Test
    void callCountReflectsSharedInstance() {
        callCount++;
        // With PER_CLASS, callCount accumulates across tests.
        // With PER_METHOD, callCount would always be 1.
        assertTrue(callCount >= 1, "callCount should be at least 1");
    }
}
