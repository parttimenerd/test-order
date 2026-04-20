package com.example.coverage;

public class StringProcessor {
    public static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public static String toUpperCase(String s) {
        return s == null ? null : s.toUpperCase();
    }

    public static String toLowerCase(String s) {
        return s == null ? null : s.toLowerCase();
    }

    public static boolean isPalindrome(String s) {
        if (s == null) return false;
        String cleaned = s.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return cleaned.equals(reverse(cleaned));
    }

    public static int countVowels(String s) {
        if (s == null) return 0;
        int count = 0;
        for (char c : s.toLowerCase().toCharArray()) {
            if ("aeiou".indexOf(c) >= 0) count++;
        }
        return count;
    }
}
