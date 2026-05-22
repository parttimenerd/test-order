package com.example.app;

/**
 * Standalone string utility — no dependency on MathHelper or Calculator.
 */
public class StringUtils {

    public String reverse(String input) {
        if (input == null) return null;
        return new StringBuilder(input).reverse().toString();
    }

    public boolean isPalindrome(String input) {
        if (input == null) return false;
        String cleaned = input.toLowerCase().replaceAll("[^a-z0-9]", "");
        return cleaned.contentEquals(new StringBuilder(cleaned).reverse());
    }
}
