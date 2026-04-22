package com.example.util;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class StringHelperTest {

    @Test
    public void testReverse() {
        assertEquals(StringHelper.reverse("hello"), "olleh");
    }

    @Test
    public void testReverseNull() {
        assertNull(StringHelper.reverse(null));
    }

    @Test
    public void testIsPalindrome() {
        assertTrue(StringHelper.isPalindrome("racecar"));
        assertFalse(StringHelper.isPalindrome("hello"));
    }

    @Test
    public void testPalindromeWithSpaces() {
        assertTrue(StringHelper.isPalindrome("nurses run"));
    }

    @Test
    public void testCountVowels() {
        assertEquals(StringHelper.countVowels("hello world"), 3);
    }

    @Test
    public void testCountVowelsNull() {
        assertEquals(StringHelper.countVowels(null), 0);
    }
}
