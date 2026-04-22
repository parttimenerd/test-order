package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * P6-TESTNG-006: Gradle-based TestNG project
 */
public class GradleTestNGTest {

    @Test
    public void gradleTest1() {
        Assert.assertTrue(true);
    }

    @Test
    public void gradleTest2() {
        Assert.assertEquals(1 + 1, 2);
    }

    @Test
    public void gradleTest3() {
        Assert.assertNotNull("test");
    }
}
