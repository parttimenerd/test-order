package com.example;

import java.util.HashMap;
import java.util.Map;

public class DatabaseHelper {
    private static Map<String, String> store = new HashMap<>();

    public void save(String key, String value) {
        store.put(key, value);
    }

    public String get(String key) {
        return store.get(key);
    }

    public void delete(String key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }
}
