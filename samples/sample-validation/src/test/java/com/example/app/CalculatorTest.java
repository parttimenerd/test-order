package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Calculator — depends on Calculator and Parser. */
class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void testSum() {
        assertEquals(6, calculator.sum("1, 2, 3"));
    }

    @Test
    void testSumSingle() {
        assertEquals(42, calculator.sum("42"));
    }

    @Test
    void testProduct() {
        assertEquals(24, calculator.product("2, 3, 4"));
    }

    @Test
    void testAdd() {
        assertEquals(7, calculator.add(3, 4));
    }
}
