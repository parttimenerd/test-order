package com.example.od;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Warms up the CacheManager — this is the "setter" for the cache dependency chain.
 * Other tests depend on this running first.
 */
class CacheWarmupTest {

    @Test
    void warmUpCache() {
        CacheManager.warmUp();
        assertTrue(CacheManager.isWarmedUp());
        assertEquals(3, CacheManager.size());
    }

    @Test
    void addExtraEntries() {
        CacheManager.put("feature.flag.x", "enabled");
        assertNotNull(CacheManager.get("feature.flag.x"));
    }
}
