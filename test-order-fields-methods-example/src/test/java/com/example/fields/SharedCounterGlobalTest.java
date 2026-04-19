package com.example.fields;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SharedCounter - uses both static and instance counters.
 * Demonstrates mixed field dependency scenario.
 */
class SharedCounterGlobalTest {
    
    @BeforeEach
    void setUp() {
        SharedCounter.resetGlobal();
    }
    
    @AfterEach
    void tearDown() {
        SharedCounter.resetGlobal();
    }
    
    @Test
    void testGlobalIncrement() {
        SharedCounter.incrementGlobal();
        assertEquals(1, SharedCounter.getGlobalCounter());
    }
    
    @Test
    void testGlobalMultipleIncrements() {
        SharedCounter.incrementGlobal();
        SharedCounter.incrementGlobal();
        SharedCounter.incrementGlobal();
        assertEquals(3, SharedCounter.getGlobalCounter());
    }
    
    @Test
    void testGlobalCounter() {
        for (int i = 0; i < 10; i++) {
            SharedCounter.incrementGlobal();
        }
        assertEquals(10, SharedCounter.getGlobalCounter());
    }
}
