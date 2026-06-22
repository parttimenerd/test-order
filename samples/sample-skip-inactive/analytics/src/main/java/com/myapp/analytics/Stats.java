package com.myapp.analytics;

public class Stats {
    public static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return values.length == 0 ? 0 : sum / values.length;
    }
}
