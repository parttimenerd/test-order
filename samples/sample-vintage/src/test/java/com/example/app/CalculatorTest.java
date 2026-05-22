package com.example.app;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.Ignore;

/**
 * JUnit 4 test for Calculator — depends on Calculator and MathHelper.
 * Runs via JUnit Vintage engine on the JUnit Platform.
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

    @Test
    @Ignore("Not implemented yet")
    public void testIgnored() {
        fail("Should not run");
    }

    @Test
    public void testPower() {
        assertEquals(8, calculator.power(2, 3));
    }
}
