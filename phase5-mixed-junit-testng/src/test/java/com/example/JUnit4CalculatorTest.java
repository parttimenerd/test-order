package com.example;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class JUnit4CalculatorTest {
    private Calculator calculator;

    @Before
    public void setUp() {
        calculator = new Calculator();
    }

    @Test
    public void testAddition() {
        int result = calculator.add(2, 3);
        assertEquals(5, result);
    }

    @Test
    public void testSubtraction() {
        int result = calculator.subtract(5, 3);
        assertEquals(2, result);
    }

    @Test
    public void testMultiplication() {
        int result = calculator.multiply(4, 5);
        assertEquals(20, result);
    }
}
