package com.example.app;

/**
 * Calculator that delegates to MathHelper.
 */
public class Calculator {

    private final MathHelper mathHelper = new MathHelper();

    public int add(int a, int b) {
        return mathHelper.add(a, b);
    }

    public int multiply(int a, int b) {
        return mathHelper.multiply(a, b);
    }
}
