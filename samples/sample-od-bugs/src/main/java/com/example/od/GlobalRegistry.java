package com.example.od;

/**
 * Shared mutable singleton — the source of OD bugs.
 * Tests that read/write this state without proper isolation are order-dependent.
 */
public class GlobalRegistry {
    private static GlobalRegistry instance;
    private int counter = 0;
    private String lastUser = null;
    private boolean initialized = false;

    private GlobalRegistry() {}

    public static GlobalRegistry getInstance() {
        if (instance == null) {
            instance = new GlobalRegistry();
        }
        return instance;
    }

    /** Reset for testing — but not all tests call this! */
    public static void reset() {
        instance = null;
    }

    public void initialize(String user) {
        this.lastUser = user;
        this.initialized = true;
        this.counter = 0;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getLastUser() {
        return lastUser;
    }

    public int increment() {
        return ++counter;
    }

    public int getCounter() {
        return counter;
    }
}
