package com.example.od;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Reads from CacheManager expecting it to be warmed up.
 * BRITTLE: fails if CacheWarmupTest hasn't run first.
 */
class CacheConsumerTest {

    @Test
    void readsTimeout() {
        // Depends on CacheWarmupTest.warmUpCache() having run
        assertTrue(CacheManager.isWarmedUp(), "Cache should be warmed up");
        assertEquals("30", CacheManager.get("config.timeout"));
    }

    @Test
    void readsRetries() {
        assertEquals("3", CacheManager.get("config.retries"));
    }

    @Test
    void readsBaseUrl() {
        String url = CacheManager.get("config.baseUrl");
        assertNotNull(url, "baseUrl should be cached");
        assertTrue(url.startsWith("http"));
    }
}
