package com.myapp.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {
    private final Validator validator = new Validator();

    @Test
    void testValidEmail() {
        assertTrue(validator.isValidEmail("user@test.com"));
        assertFalse(validator.isValidEmail("invalid"));
        assertFalse(validator.isValidEmail(null));
    }

    @Test
    void testValidName() {
        assertTrue(validator.isValidName("Alice"));
        assertFalse(validator.isValidName(""));
        assertFalse(validator.isValidName(null));
    }

    @Test
    void testIsPositive() {
        assertTrue(validator.isPositive(1.0));
        assertFalse(validator.isPositive(0.0));
        assertFalse(validator.isPositive(-1.0));
    }
}
