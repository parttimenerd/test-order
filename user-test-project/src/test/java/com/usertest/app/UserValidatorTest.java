package com.usertest.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserValidator Tests")
class UserValidatorTest {
    private final UserValidator validator = new UserValidator();

    @Test
    @DisplayName("Email validation")
    void testEmailValidation() {
        assertTrue(validator.isValidEmail("user@example.com"));
        assertFalse(validator.isValidEmail("invalid.email"));
        assertFalse(validator.isValidEmail("@"));
        assertFalse(validator.isValidEmail(null));
    }

    @Test
    @DisplayName("Password validation")
    void testPasswordValidation() {
        assertTrue(validator.isValidPassword("password123"));
        assertFalse(validator.isValidPassword("short"));
        assertFalse(validator.isValidPassword(""));
        assertFalse(validator.isValidPassword(null));
    }

    @Test
    @DisplayName("Username validation")
    void testUsernameValidation() {
        assertTrue(validator.isValidUsername("validuser"));
        assertTrue(validator.isValidUsername("abc"));
        assertFalse(validator.isValidUsername("ab"));
        assertFalse(validator.isValidUsername("thisusernameistoolongforthelimit"));
        assertFalse(validator.isValidUsername(null));
    }

    @Test
    @DisplayName("Combined validations")
    void testCombinedValidations() {
        assertTrue(validator.isValidEmail("test@domain.com"));
        assertTrue(validator.isValidPassword("pass1234"));
        assertTrue(validator.isValidUsername("testuser"));
    }
}
