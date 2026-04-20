package com.example.test;

import com.example.Calculator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class CalculatorTest {
    // User modified this test
    private Calculator calc = new Calculator();

    @Test
    public void testAdd() {
        assertThat(calc.add(2, 3)).isEqualTo(5);
    }

    @Test
    public void testSubtract() {
        assertThat(calc.subtract(5, 3)).isEqualTo(2);
    }

    @Test
    public void testMultiply() {
        assertThat(calc.multiply(4, 5)).isEqualTo(20);
    }

    @Test
    public void testDivide() {
        assertThat(calc.divide(10, 2)).isEqualTo(5);
    }
}
