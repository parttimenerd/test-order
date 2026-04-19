package com.example.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Calculator — depends on Calculator and MathHelper.
 */
class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void testAdd() {
        assertEquals(5, calculator.add(2, 3));
    }

    @Test
    void testMultiply() {
        assertEquals(12, calculator.multiply(3, 4));
    }

    @Test
    void testPower() {
        assertEquals(8, calculator.power(2, 3));
    }
}
