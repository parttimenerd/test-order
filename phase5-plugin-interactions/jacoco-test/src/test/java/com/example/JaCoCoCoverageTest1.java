package com.example;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class JaCoCoCoverageTest1 {
    static {
        System.out.println("JaCoCoCoverageTest1 static init");
    }
    
    @BeforeClass
    public static void setup() {
        System.out.println("JaCoCoCoverageTest1 setup");
    }
    
    @Test
    public void testA() {
        assertTrue(true);
    }
    
    @Test
    public void testB() {
        assertTrue(true);
    }
    
    @Test
    public void testC() {
        assertTrue(true);
    }
}
