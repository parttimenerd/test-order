package com.myapp.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StringUtilsTest {
    @Test
    void testCapitalize() {
        assertEquals("Hello", StringUtils.capitalize("hello"));
        assertEquals("A", StringUtils.capitalize("a"));
        assertNull(StringUtils.capitalize(null));
        assertEquals("", StringUtils.capitalize(""));
    }

    @Test
    void testReverse() {
        assertEquals("olleh", StringUtils.reverse("hello"));
        assertNull(StringUtils.reverse(null));
    }

    @Test
    void testIsPalindrome() {
        assertTrue(StringUtils.isPalindrome("racecar"));
        assertTrue(StringUtils.isPalindrome("A man a plan a canal Panama"));
        assertFalse(StringUtils.isPalindrome("hello"));
        assertFalse(StringUtils.isPalindrome(null));
    }
}
