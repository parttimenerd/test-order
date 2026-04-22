package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StringUtilsTest {
    
    @Test
    public void testReverseSimple() {
        String result = StringUtils.reverse("hello");
        assertEquals("olleh", result);
    }
    
    @Test
    public void testReverseEmpty() {
        String result = StringUtils.reverse("");
        assertEquals("", result);
    }
    
    @Test
    public void testIsPalindrome() {
        assertTrue(StringUtils.isPalindrome("racecar"));
        assertTrue(StringUtils.isPalindrome("a"));
        assertFalse(StringUtils.isPalindrome("hello"));
    }
}
