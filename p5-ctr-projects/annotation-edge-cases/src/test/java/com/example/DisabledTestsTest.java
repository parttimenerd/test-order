package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class with @Disabled tests mixed with active tests.
 * Tests if test-order correctly handles disabled tests.
 */
@DisplayName("Disabled and Enabled Tests")
public class DisabledTestsTest {
    @Test
    @DisplayName("Enabled test 1")
    void enabledTest1() {
        assertTrue(true);
    }

    @Test
    @Disabled("Temporary disable")
    @DisplayName("Disabled test - should not run")
    void disabledTest() {
        fail("This should not run");
    }

    @Test
    @DisplayName("Enabled test 2")
    void enabledTest2() {
        assertEquals(2, 2);
    }

    @Disabled
    @Test
    @DisplayName("Disabled at class level")
    void disabledAtClassLevel() {
        fail("This should not run either");
    }
}
