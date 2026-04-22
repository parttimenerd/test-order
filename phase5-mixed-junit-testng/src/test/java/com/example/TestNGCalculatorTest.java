package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;

public class TestNGCalculatorTest {
    private Calculator calculator;

    @BeforeMethod
    public void setUp() {
        calculator = new Calculator();
    }

    @Test
    public void testDivision() {
        int result = calculator.divide(10, 2);
        Assert.assertEquals(result, 5);
    }

    @Test
    public void testDivisionByZero() {
        try {
            calculator.divide(10, 0);
            Assert.fail("Should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testNegativeDivision() {
        int result = calculator.divide(-10, 2);
        Assert.assertEquals(result, -5);
    }
}
