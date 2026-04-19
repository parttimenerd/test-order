package com.example.app;

/** Standalone parser — no dependencies on other project classes. */
public class Parser {

    public int[] parseInts(String csv) {
        if (csv == null || csv.isBlank()) return new int[0];
        String[] parts = csv.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    public String formatInts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.toString();
    }
}
