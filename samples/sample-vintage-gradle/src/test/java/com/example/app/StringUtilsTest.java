package com.example.app;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JUnit 4 test for StringUtils — runs via Vintage engine.
 */
public class StringUtilsTest {

    private final StringUtils stringUtils = new StringUtils();

    @Test
    public void testReverse() {
        assertEquals("olleh", stringUtils.reverse("hello"));
    }

    @Test
    public void testIsPalindrome() {
        assertTrue(stringUtils.isPalindrome("racecar"));
        assertFalse(stringUtils.isPalindrome("hello"));
    }
}
