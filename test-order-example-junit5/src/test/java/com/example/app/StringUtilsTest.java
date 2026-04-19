package com.example.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests StringUtils — depends only on StringUtils (no MathHelper/Calculator).
 */
class StringUtilsTest {

    private final StringUtils stringUtils = new StringUtils();

    @Test
    void testReverse() {
        assertEquals("olleh", stringUtils.reverse("hello"));
    }

    @Test
    void testReverseNull() {
        assertNull(stringUtils.reverse(null));
    }

    @Test
    void testIsPalindrome() {
        assertTrue(stringUtils.isPalindrome("racecar"));
        assertFalse(stringUtils.isPalindrome("hello"));
    }

    @Test
    void testCapitalize() {
        assertEquals("Hello", stringUtils.capitalize("hello"));
    }
}
