package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * P6-TESTNG-004: Test dependencies and method ordering
 * Tests if test-order respects @dependsOnMethods
 */
public class AdvancedDependenciesTest {

    @Test
    public void testSetupBase() {
        Assert.assertTrue(true);
    }

    @Test(dependsOnMethods = {"testSetupBase"})
    public void testDependsOnSetup() {
        Assert.assertTrue(true);
    }

    @Test
    public void testAnotherBase() {
        Assert.assertTrue(true);
    }

    @Test(dependsOnMethods = {"testAnotherBase", "testSetupBase"})
    public void testDependsOnMultiple() {
        Assert.assertTrue(true);
    }

    @Test
    public void testIndependent() {
        Assert.assertTrue(true);
    }
}
