package com.example;

public class StringUtils {
    public static String reverse(String str) {
        return new StringBuilder(str).reverse().toString();
    }

    public static boolean isPalindrome(String str) {
        return str.equals(reverse(str));
    }

    public static int countVowels(String str) {
        int count = 0;
        for (char c : str.toLowerCase().toCharArray()) {
            if ("aeiou".indexOf(c) >= 0) {
                count++;
            }
        }
        return count;
    }
}
