package com.example;

public class Validator {
    private final Calculator calculator;
    private final Parser parser;

    public Validator(Calculator calculator, Parser parser) {
        this.calculator = calculator;
        this.parser = parser;
    }

    public boolean validateSum(String input, int expectedSum) {
        int[] numbers = parser.parseNumbers(input);
        int sum = 0;
        for (int num : numbers) {
            sum = calculator.add(sum, num);
        }
        return sum == expectedSum;
    }

    public int calculateProduct(String input) {
        int[] numbers = parser.parseNumbers(input);
        int product = 1;
        for (int num : numbers) {
            product = calculator.multiply(product, num);
        }
        return product;
    }
}
