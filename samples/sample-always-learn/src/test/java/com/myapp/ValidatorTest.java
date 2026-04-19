package com.myapp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Validator — independent from Greeter/MessageFormatter. */
class ValidatorTest {

    @Test
    void validName() {
        var v = new Validator();
        assertTrue(v.isValidName("Alice"));
    }

    @Test
    void nullNameInvalid() {
        var v = new Validator();
        assertFalse(v.isValidName(null));
    }

    @Test
    void blankNameInvalid() {
        var v = new Validator();
        assertFalse(v.isValidName("   "));
    }

    @Test
    void validAge() {
        var v = new Validator();
        assertTrue(v.isValidAge(25));
    }

    @Test
    void negativeAgeInvalid() {
        var v = new Validator();
        assertFalse(v.isValidAge(-1));
    }
}
