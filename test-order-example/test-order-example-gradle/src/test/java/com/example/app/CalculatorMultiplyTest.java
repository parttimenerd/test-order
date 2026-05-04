package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorMultiplyTest {
    @Test
    void testMultiply() {
        Calculator calc = new Calculator();
        assertEquals(6, calc.multiply(2, 3));
    }

    @Test
    void testMultiplyByZero() {
        Calculator calc = new Calculator();
        assertEquals(0, calc.multiply(5, 0));
    }
}
