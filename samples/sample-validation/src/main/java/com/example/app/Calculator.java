package com.example.app;

/** Calculator that delegates parsing to Parser. Depends on: Parser. */
public class Calculator {

    private final Parser parser = new Parser();

    public int sum(String csv) {
        int[] values = parser.parseInts(csv);
        int total = 0;
        for (int v : values) total += v;
        return total;
    }

    public int product(String csv) {
        int[] values = parser.parseInts(csv);
        int result = 1;
        for (int v : values) result *= v;
        return result;
    }

    public int add(int a, int b) {
        return a + b;
    }
}
