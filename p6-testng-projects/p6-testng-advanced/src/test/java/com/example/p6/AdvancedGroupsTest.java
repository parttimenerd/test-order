package com.example.p6;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * P6-TESTNG-003: Test groups and priorities
 */
public class AdvancedGroupsTest {

    @Test(groups = {"smoke"}, priority = 1)
    public void smokeTestHighPriority() {
        Assert.assertTrue(true);
    }

    @Test(groups = {"smoke", "quick"}, priority = 2)
    public void smokeTestMediumPriority() {
        Assert.assertTrue(true);
    }

    @Test(groups = {"integration"}, priority = 3)
    public void integrationTestLowPriority() {
        Assert.assertTrue(true);
    }

    @Test(groups = {"integration", "slow"}, priority = 10)
    public void slowIntegrationTest() {
        Assert.assertTrue(true);
    }

    @Test(groups = {"unit"})
    public void unitTestNoPriority() {
        Assert.assertTrue(true);
    }
}
