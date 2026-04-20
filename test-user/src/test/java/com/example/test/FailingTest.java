package com.example.test;

import com.example.Calculator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class FailingTest {
    private Calculator calc = new Calculator();

    @Test
    public void testAddMakesFailure() {
        // This test will fail
        assertThat(calc.add(2, 3)).isEqualTo(10);  // Wrong expectation
    }

    @Test
    public void testSubtract() {
        assertThat(calc.subtract(5, 3)).isEqualTo(2);
    }
}
