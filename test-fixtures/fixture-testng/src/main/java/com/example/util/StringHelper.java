package com.example.util;

/**
 * Simple utility class for integration testing test-order with TestNG.
 */
public class StringHelper {

    public static String reverse(String input) {
        if (input == null) return null;
        return new StringBuilder(input).reverse().toString();
    }

    public static boolean isPalindrome(String input) {
        if (input == null) return false;
        String cleaned = input.replaceAll("\\s+", "").toLowerCase();
        return cleaned.equals(new StringBuilder(cleaned).reverse().toString());
    }

    public static int countVowels(String input) {
        if (input == null) return 0;
        int count = 0;
        for (char c : input.toLowerCase().toCharArray()) {
            if ("aeiou".indexOf(c) >= 0) count++;
        }
        return count;
    }
}
