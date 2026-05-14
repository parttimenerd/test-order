package com.example.od;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Independent test — always passes regardless of order. (Control)
 */
class IndependentTest {

    @Test
    void pureMath() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void stringOps() {
        assertEquals("hello world", "hello" + " " + "world");
    }

    @Test
    void newRegistryInstanceWorks() {
        // Creates fresh state, does not depend on singleton
        GlobalRegistry.reset();
        GlobalRegistry reg = GlobalRegistry.getInstance();
        assertNotNull(reg);
        assertFalse(reg.isInitialized());
    }
}
