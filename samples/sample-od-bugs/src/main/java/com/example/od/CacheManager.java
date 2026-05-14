package com.example.od;

import java.util.HashMap;
import java.util.Map;

/**
 * Another shared mutable singleton — a second source of OD bugs.
 * Creates a second independent dependency chain.
 */
public class CacheManager {
    private static final Map<String, String> cache = new HashMap<>();
    private static boolean warmedUp = false;

    public static void warmUp() {
        cache.put("config.timeout", "30");
        cache.put("config.retries", "3");
        cache.put("config.baseUrl", "http://localhost:8080");
        warmedUp = true;
    }

    public static boolean isWarmedUp() {
        return warmedUp;
    }

    public static String get(String key) {
        return cache.get(key);
    }

    public static void put(String key, String value) {
        cache.put(key, value);
    }

    public static int size() {
        return cache.size();
    }

    public static void clear() {
        cache.clear();
        warmedUp = false;
    }
}
