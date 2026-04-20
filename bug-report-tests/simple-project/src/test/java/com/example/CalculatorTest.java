package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CalculatorTest {
    private Calculator calc = new Calculator();

    @Test
    public void test1() {
        assertEquals(5, calc.add(2, 3));
    }

    @Test
    public void test2() {
        assertEquals(1, calc.add(0, 1));
    }
}
