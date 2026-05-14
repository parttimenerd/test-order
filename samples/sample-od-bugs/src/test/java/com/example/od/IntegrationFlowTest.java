package com.example.od;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Depends on BOTH GlobalRegistry (from SetupTest) AND CacheManager (from CacheWarmupTest).
 * This creates a cross-cutting dependency — the most complex OD pattern.
 */
class IntegrationFlowTest {

    @Test
    void fullFlowRequiresBothSystems() {
        // Needs GlobalRegistry initialized (by SetupTest)
        GlobalRegistry reg = GlobalRegistry.getInstance();
        assertTrue(reg.isInitialized(), "Registry must be initialized");

        // Needs CacheManager warmed up (by CacheWarmupTest)
        assertTrue(CacheManager.isWarmedUp(), "Cache must be warmed up");

        // Cross-system check
        String user = reg.getLastUser();
        assertNotNull(user);
        CacheManager.put("last.active.user", user);
        assertEquals(user, CacheManager.get("last.active.user"));
    }

    @Test
    void counterAndCacheInteraction() {
        GlobalRegistry reg = GlobalRegistry.getInstance();
        int count = reg.increment();
        CacheManager.put("op.count", String.valueOf(count));
        assertNotNull(CacheManager.get("op.count"));
    }
}
