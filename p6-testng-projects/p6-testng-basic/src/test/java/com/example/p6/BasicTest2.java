package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;

/**
 * P6-TESTNG-001: Second test class for ordering verification
 */
public class BasicTest2 {
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
    public void testA_first() {
        int sum = utils.add(5, 5);
        Assert.assertEquals(sum, 10);
    }

    @Test
    public void testB_second() {
        String reversed = utils.reverse("abc");
        Assert.assertEquals(reversed, "cba");
    }

    @Test
    public void testC_third() {
        Assert.assertTrue(utils.isPrime(7));
    }

    @Test
    public void testD_fourth() {
        int[] arr = utils.createArray(3);
        Assert.assertEquals(arr.length, 3);
    }

    @Test
    public void testE_fifth() {
        long result = utils.factorial(5);
        Assert.assertEquals(result, 120L);
    }
}
