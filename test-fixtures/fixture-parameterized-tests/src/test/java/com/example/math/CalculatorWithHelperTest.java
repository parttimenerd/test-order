package com.example.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that use shared test utility methods. Changes to TestHelper should
 * cause this test class to be scored higher via depOverlap.
 */
public class CalculatorWithHelperTest {

    @Test
    void testAdditionWithHelper() {
        TestHelper.assertCalculation("1 + 1", 2, Calculator.add(1, 1));
        TestHelper.assertCalculation("10 + 20", 30, Calculator.add(10, 20));
    }

    @Test
    void testMultiplicationWithHelper() {
        TestHelper.assertCalculation("3 * 4", 12, Calculator.multiply(3, 4));
        TestHelper.assertCalculation("7 * 8", 56, Calculator.multiply(7, 8));
    }

    @Test
    void testResultsInRange() {
        TestHelper.assertInRange(Calculator.add(1, 2), 0, 10);
        TestHelper.assertInRange(Calculator.multiply(2, 3), 0, 10);
    }
}
