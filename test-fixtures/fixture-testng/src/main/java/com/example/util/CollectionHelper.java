package com.example.util;

/**
 * Collection utility class for integration testing.
 */
public class CollectionHelper {

    public static int sum(int[] values) {
        int total = 0;
        for (int v : values) total += v;
        return total;
    }

    public static double average(int[] values) {
        if (values == null || values.length == 0) return 0.0;
        return (double) sum(values) / values.length;
    }

    public static int max(int[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("Empty array");
        int max = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] > max) max = values[i];
        }
        return max;
    }
}
