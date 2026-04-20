package com.example;

public class StringProcessor {
    public String toUpperCase(String input) {
        return input.toUpperCase();
    }

    public String toLowerCase(String input) {
        return input.toLowerCase();
    }

    public String reverse(String input) {
        return new StringBuilder(input).reverse().toString();
    }

    public int countWords(String input) {
        if (input == null || input.isEmpty()) return 0;
        return input.split("\\s+").length;
    }
}
