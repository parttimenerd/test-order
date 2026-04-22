package com.example;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

public class EarlyJUnit4Test {
    
    private String value;
    
    @BeforeClass
    public static void setUpClass() {
        System.out.println("Early JUnit 4 - before class");
    }
    
    @AfterClass
    public static void tearDownClass() {
        System.out.println("Early JUnit 4 - after class");
    }
    
    @Before
    public void setUp() {
        value = "initialized";
    }
    
    @After
    public void tearDown() {
        value = null;
    }
    
    @Test
    public void testInitialized() {
        assertEquals("initialized", value);
    }
    
    @Test
    public void testNotNull() {
        assertNotNull(value);
    }
    
    @Test
    public void testMultipleAssertions() {
        assertNotNull(value);
        assertEquals("initialized", value);
        assertTrue(value.length() > 0);
    }
}
