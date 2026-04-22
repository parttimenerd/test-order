package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;

/**
 * P6-TESTNG-001: Basic test discovery and execution
 */
public class BasicTest1 {
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
    public void test001_first() {
        Assert.assertTrue(utils.isReady());
    }

    @Test
    public void test002_second() {
        int result = utils.add(1, 2);
        Assert.assertEquals(result, 3);
    }

    @Test
    public void test003_third() {
        String msg = utils.greet("World");
        Assert.assertTrue(msg.contains("World"));
    }

    @Test
    public void test004_fourth() {
        Assert.assertFalse(utils.isEmpty("test"));
    }

    @Test
    public void test005_fifth() {
        double result = utils.divide(10.0, 2.0);
        Assert.assertEquals(result, 5.0);
    }
}
