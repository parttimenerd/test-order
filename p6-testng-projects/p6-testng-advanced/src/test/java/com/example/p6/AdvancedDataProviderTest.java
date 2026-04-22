package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * P6-TESTNG-002: @DataProvider parameterized tests
 * Tests if test-order can handle DataProvider-generated test instances
 */
public class AdvancedDataProviderTest {

    @DataProvider(name = "arithmeticData")
    public Object[][] arithmeticData() {
        return new Object[][] {
            {2, 3, 5},
            {10, 5, 15},
            {100, 100, 200},
        };
    }

    @Test(dataProvider = "arithmeticData")
    public void testAddition(int a, int b, int expected) {
        Assert.assertEquals(a + b, expected);
    }

    @DataProvider(name = "stringData")
    public Object[][] stringData() {
        return new Object[][] {
            {"hello", 5},
            {"world", 5},
            {"test", 4},
        };
    }

    @Test(dataProvider = "stringData")
    public void testStringLength(String str, int expectedLen) {
        Assert.assertEquals(str.length(), expectedLen);
    }

    @DataProvider(name = "booleanData")
    public Object[][] booleanData() {
        return new Object[][] {
            {true},
            {false},
        };
    }

    @Test(dataProvider = "booleanData")
    public void testBoolean(boolean value) {
        Assert.assertNotNull(value);
    }
}
