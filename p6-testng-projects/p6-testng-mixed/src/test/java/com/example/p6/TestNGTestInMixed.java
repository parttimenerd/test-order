package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;

/**
 * P6-TESTNG-005: TestNG tests in mixed project
 */
public class TestNGTestInMixed {
    private TestUtils utils;

    @BeforeMethod
    public void setUp() {
        utils = new TestUtils();
    }

    @AfterMethod
    public void tearDown() {
        utils = null;
    }

    @Test
    public void testng_testMultiplication() {
        int result = utils.add(4, 5);
        Assert.assertEquals(result, 9);
    }

    @Test
    public void testng_testPrimality() {
        boolean prime = utils.isPrime(13);
        Assert.assertTrue(prime);
    }

    @Test
    public void testng_testFactorial() {
        long result = utils.factorial(4);
        Assert.assertEquals(result, 24L);
    }
}
