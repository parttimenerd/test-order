package com.example.od;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * POLLUTER: clears the cache, causing downstream tests to fail.
 * Tests that run after this will see an empty cache.
 */
class CacheCleanerTest {

    @Test
    void clearCacheForIsolation() {
        // "Cleaning up" but actually polluting for downstream tests
        CacheManager.clear();
        assertFalse(CacheManager.isWarmedUp());
        assertEquals(0, CacheManager.size());
    }

    @Test
    void verifyCacheIsEmpty() {
        assertNull(CacheManager.get("config.timeout"));
    }
}
