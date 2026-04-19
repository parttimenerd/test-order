package com.example.app;

public class StringUtils {
    public static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public static boolean isPalindrome(String s) {
        String reversed = reverse(s);
        return s.equalsIgnoreCase(reversed);
    }
}
