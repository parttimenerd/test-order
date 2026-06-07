package me.bechberger.it.csm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Basic sanity test for Calculator — required for offline instrumentation to activate. */
class CalculatorSanityTest {

    @Test
    void additionBasic() {
        assertEquals(0, Calculator.add(0, 0));
    }
}
