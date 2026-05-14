package com.example.od;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * This test initializes the GlobalRegistry as a side effect.
 * It acts as a "polluter" (or "setter") — other tests depend on its side effects.
 */
class SetupTest {

    @Test
    void initializesRegistry() {
        GlobalRegistry.getInstance().initialize("admin");
        assertTrue(GlobalRegistry.getInstance().isInitialized());
        assertEquals("admin", GlobalRegistry.getInstance().getLastUser());
    }

    @Test
    void incrementsCounter() {
        // This leaves counter = 1 as a side effect
        GlobalRegistry.getInstance().increment();
        assertTrue(GlobalRegistry.getInstance().getCounter() > 0);
    }
}
