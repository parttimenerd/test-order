package com.example.app;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JUnit 4 test for Calculator — runs via Vintage engine.
 */
public class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    public void testAdd() {
        assertEquals(5, calculator.add(2, 3));
    }

    @Test
    public void testMultiply() {
        assertEquals(12, calculator.multiply(3, 4));
    }
}
