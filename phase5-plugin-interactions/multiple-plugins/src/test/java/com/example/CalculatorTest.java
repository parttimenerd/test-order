package com.example;

import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CalculatorTest {
    static Calculator calc;
    
    @BeforeClass
    public static void init() {
        calc = new Calculator();
    }
    
    @Test
    public void testAdd() { assertEquals(5, calc.add(2, 3)); }
    
    @Test
    public void testSubtract() { assertEquals(1, calc.subtract(3, 2)); }
    
    @Test
    public void testMultiply() { assertEquals(6, calc.multiply(2, 3)); }
    
    @Test
    public void testDivide() { assertEquals(2, calc.divide(6, 3)); }
}
