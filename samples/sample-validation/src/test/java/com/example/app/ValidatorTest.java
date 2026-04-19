package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Validator — depends on Validator, Calculator, and Parser. */
class ValidatorTest {

    private final Validator validator = new Validator();

    @Test
    void testValidateSum() {
        assertTrue(validator.validateSum("1, 2, 3", 6));
        assertFalse(validator.validateSum("1, 2, 3", 10));
    }

    @Test
    void testAllPositive() {
        assertTrue(validator.allPositive("1, 2, 3"));
        assertFalse(validator.allPositive("1, -2, 3"));
    }

    @Test
    void testComputeAndAdd() {
        assertEquals(16, validator.computeAndAdd("1, 2, 3", 10));
    }
}
