package com.example.app;

/** Standalone formatter — no dependencies on other project classes. */
public class Formatter {

    public String toUpperCase(String input) {
        if (input == null) return null;
        return input.toUpperCase();
    }

    public String wrap(String input, String prefix, String suffix) {
        return prefix + input + suffix;
    }

    public String repeat(String input, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(input);
        }
        return sb.toString();
    }
}
