package com.example;

import junit.framework.TestCase;

public class LegacyJUnit3Test extends TestCase {
    
    private String value;
    
    public void setUp() {
        value = "initialized";
    }
    
    public void tearDown() {
        value = null;
    }
    
    // Test method using test* naming convention
    public void testValueInitialized() {
        assertEquals("initialized", value);
    }
    
    public void testValueNotNull() {
        assertNotNull(value);
    }
    
    public void testValueEquals() {
        assertTrue("Value should equal 'initialized'", 
                   value.equals("initialized"));
    }
}
