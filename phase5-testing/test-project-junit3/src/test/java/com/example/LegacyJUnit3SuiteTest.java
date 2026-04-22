package com.example;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public class LegacyJUnit3SuiteTest extends TestCase {
    
    public void testAlpha() {
        assertTrue(true);
    }
    
    public void testBeta() {
        assertTrue(true);
    }
    
    public void testGamma() {
        assertTrue(true);
    }
    
    public static Test suite() {
        TestSuite suite = new TestSuite(LegacyJUnit3SuiteTest.class);
        return suite;
    }
}
