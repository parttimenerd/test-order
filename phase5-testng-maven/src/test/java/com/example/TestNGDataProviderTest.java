package com.example;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

public class TestNGDataProviderTest {
    private Calculator calculator;

    public TestNGDataProviderTest() {
        calculator = new Calculator();
    }

    @DataProvider(name = "additionProvider")
    public Object[][] additionData() {
        return new Object[][] {
            {1, 1, 2},
            {2, 2, 4},
            {5, 3, 8},
            {10, 20, 30}
        };
    }

    @Test(dataProvider = "additionProvider")
    public void testAdditionWithDataProvider(int a, int b, int expected) {
        int result = calculator.add(a, b);
        Assert.assertEquals(result, expected);
    }

    @DataProvider(name = "multiplicationProvider")
    public Object[][] multiplicationData() {
        return new Object[][] {
            {2, 3, 6},
            {4, 5, 20},
            {10, 10, 100}
        };
    }

    @Test(dataProvider = "multiplicationProvider")
    public void testMultiplicationWithDataProvider(int a, int b, int expected) {
        int result = calculator.multiply(a, b);
        Assert.assertEquals(result, expected);
    }
}
