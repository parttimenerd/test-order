package me.bechberger.it.csm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test in the consumer module that calls into the library module.
 * The cross-module-select IT verifies that when Calculator (library module) is
 * reported as changed, this test gets selected by the affected goal.
 */
class CalculatorTest {

    @Test
    void additionWorks() {
        assertEquals(5, Calculator.add(2, 3));
    }

    @Test
    void multiplicationWorks() {
        assertEquals(6, Calculator.multiply(2, 3));
    }
}
