package com.example.od;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * VICTIM test: only passes when SetupTest has already run (initialized the registry).
 * When run alone or before SetupTest, it will throw IllegalStateException.
 */
class VictimTest {

    @Test
    void usesRegistryService() {
        // This requires GlobalRegistry to be initialized — fails if run alone!
        RegistryService service = new RegistryService();
        String greeting = service.greetUser();
        assertNotNull(greeting);
        assertTrue(greeting.contains("Hello"));
    }

    @Test
    void checksLastUser() {
        // Depends on SetupTest having set lastUser to "admin"
        assertEquals("admin", GlobalRegistry.getInstance().getLastUser());
    }
}
