package com.example.od;

/**
 * A service that depends on GlobalRegistry being initialized.
 */
public class RegistryService {

    public String greetUser() {
        GlobalRegistry reg = GlobalRegistry.getInstance();
        if (!reg.isInitialized()) {
            throw new IllegalStateException("Registry not initialized");
        }
        return "Hello, " + reg.getLastUser() + "! (visit #" + reg.increment() + ")";
    }

    public int visitCount() {
        return GlobalRegistry.getInstance().getCounter();
    }
}
