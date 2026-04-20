package com.example.coverage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StringProcessorTest {

    @Test
    public void testReverse() {
        assertEquals("olleh", StringProcessor.reverse("hello"));
        assertEquals("", StringProcessor.reverse(""));
    }

    @Test
    public void testToUpperCase() {
        assertEquals("HELLO", StringProcessor.toUpperCase("hello"));
        assertEquals("HELLO123", StringProcessor.toUpperCase("hello123"));
        assertNull(StringProcessor.toUpperCase(null));
    }

    @Test
    public void testToLowerCase() {
        assertEquals("hello", StringProcessor.toLowerCase("HELLO"));
        assertEquals("hello123", StringProcessor.toLowerCase("HELLO123"));
        assertNull(StringProcessor.toLowerCase(null));
    }

    @Test
    public void testIsPalindrome() {
        assertTrue(StringProcessor.isPalindrome("racecar"));
        assertTrue(StringProcessor.isPalindrome("A man, a plan, a canal: Panama"));
        assertFalse(StringProcessor.isPalindrome("hello"));
        assertFalse(StringProcessor.isPalindrome(null));
    }

    @Test
    public void testCountVowels() {
        assertEquals(2, StringProcessor.countVowels("hello"));
        assertEquals(5, StringProcessor.countVowels("aeiou"));
        assertEquals(0, StringProcessor.countVowels("xyz"));
        assertEquals(0, StringProcessor.countVowels(null));
    }
}
