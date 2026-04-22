package com.example;

import junit.framework.TestCase;
import org.junit.Test;
import static org.junit.Assert.*;

public class MixedJUnit3and4Test extends TestCase {
    
    // JUnit 3 style - setUp/tearDown
    public void setUp() {
        System.out.println("Setup called");
    }
    
    public void tearDown() {
        System.out.println("Teardown called");
    }
    
    // JUnit 3 style - test* method
    public void testJUnit3Style() {
        assertTrue(true);
    }
    
    // JUnit 4 style - @Test annotation
    @Test
    public void testJUnit4Style() {
        assertTrue(true);
    }
    
    // Another JUnit 4 style
    @Test
    public void anotherTestWithAnnotation() {
        assertEquals(1, 1);
    }
}
