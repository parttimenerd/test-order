package com.example.app;

/** Validator that uses Calculator and Parser. Depends on: Calculator, Parser. */
public class Validator {

    private final Calculator calculator = new Calculator();
    private final Parser parser = new Parser();

    public boolean validateSum(String csv, int expected) {
        return calculator.sum(csv) == expected;
    }

    public boolean allPositive(String csv) {
        for (int v : parser.parseInts(csv)) {
            if (v <= 0) return false;
        }
        return true;
    }

    public int computeAndAdd(String csv, int extra) {
        return calculator.add(calculator.sum(csv), extra);
    }
}
