package com.myapp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests MathService — completely independent from other classes. */
class MathServiceTest {

    @Test
    void addition() {
        var m = new MathService();
        assertEquals(5, m.add(2, 3));
    }

    @Test
    void multiplication() {
        var m = new MathService();
        assertEquals(12, m.multiply(3, 4));
    }

    @Test
    void division() {
        var m = new MathService();
        assertEquals(2.5, m.divide(5.0, 2.0));
    }

    @Test
    void divisionByZeroThrows() {
        var m = new MathService();
        assertThrows(ArithmeticException.class, () -> m.divide(1, 0));
    }
}
