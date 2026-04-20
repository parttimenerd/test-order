package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CalculatorTest {
    private Calculator calc = new Calculator();

    @Test
    public void testAdd() {
        assertEquals(5, calc.add(2, 3));
        assertEquals(0, calc.add(0, 0));
        assertEquals(-1, calc.add(-2, 1));
    }

    @Test
    public void testSubtract() {
        assertEquals(-1, calc.subtract(2, 3));
        assertEquals(0, calc.subtract(5, 5));
        assertEquals(10, calc.subtract(15, 5));
    }

    @Test
    public void testMultiply() {
        assertEquals(6, calc.multiply(2, 3));
        assertEquals(0, calc.multiply(0, 100));
        assertEquals(-6, calc.multiply(-2, 3));
    }

    @Test
    public void testAddCornerCases() {
        assertEquals(Integer.MAX_VALUE - 1, calc.add(Integer.MAX_VALUE - 2, 1));
    }
}
