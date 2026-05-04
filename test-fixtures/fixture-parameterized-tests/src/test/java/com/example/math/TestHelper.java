package com.example.math;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A test utility class that provides shared assertion helpers.
 * Changes to this class should cause tests depending on it to be prioritized.
 */
public class TestHelper {

    /**
     * Asserts that a value is within a range (inclusive).
     */
    public static void assertInRange(int value, int min, int max) {
        assertTrue(value >= min && value <= max,
                "Expected " + value + " to be in range [" + min + ", " + max + "]");
    }

    /**
     * Asserts that a calculation result matches with a description.
     */
    public static void assertCalculation(String description, int expected, int actual) {
        assertEquals(expected, actual, description);
    }
}
