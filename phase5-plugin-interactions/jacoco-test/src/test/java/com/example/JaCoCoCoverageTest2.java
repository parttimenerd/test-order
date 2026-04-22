package com.example;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class JaCoCoCoverageTest2 {
    static {
        System.out.println("JaCoCoCoverageTest2 static init");
    }
    
    private CalculatorService calc = new CalculatorService();
    
    @BeforeClass
    public static void setup() {
        System.out.println("JaCoCoCoverageTest2 setup");
    }
    
    @Test
    public void testAddition() {
        assertEquals(7, calc.add(3, 4));
    }
    
    @Test
    public void testSubtraction() {
        assertEquals(1, calc.subtract(5, 4));
    }
    
    @Test
    public void testMultiplication() {
        assertEquals(15, calc.multiply(3, 5));
    }
}
