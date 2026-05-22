package com.example.app;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JUnit 4 test for StringUtils — depends only on StringUtils.
 * Runs via JUnit Vintage engine on the JUnit Platform.
 */
public class StringUtilsTest {

    private final StringUtils stringUtils = new StringUtils();

    @Test
    public void testReverse() {
        assertEquals("olleh", stringUtils.reverse("hello"));
    }

    @Test
    public void testReverseNull() {
        assertNull(stringUtils.reverse(null));
    }

    @Test
    public void testIsPalindrome() {
        assertTrue(stringUtils.isPalindrome("racecar"));
        assertFalse(stringUtils.isPalindrome("hello"));
    }
}
