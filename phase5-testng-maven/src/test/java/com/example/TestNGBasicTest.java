package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;

public class TestNGBasicTest {
    private Calculator calculator;

    @BeforeMethod
    public void setUp() {
        calculator = new Calculator();
    }

    @AfterMethod
    public void tearDown() {
        calculator = null;
    }

    @Test
    public void testAddition() {
        int result = calculator.add(2, 3);
        Assert.assertEquals(result, 5);
    }

    @Test
    public void testSubtraction() {
        int result = calculator.subtract(5, 3);
        Assert.assertEquals(result, 2);
    }

    @Test
    public void testMultiplication() {
        int result = calculator.multiply(4, 5);
        Assert.assertEquals(result, 20);
    }

    @Test
    public void testDivision() {
        int result = calculator.divide(10, 2);
        Assert.assertEquals(result, 5);
    }

    @Test(enabled = true)
    public void testSkippedTest() {
        Assert.assertTrue(true);
    }
}
