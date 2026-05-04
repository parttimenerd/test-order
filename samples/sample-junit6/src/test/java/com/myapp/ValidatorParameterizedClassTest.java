package com.myapp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link Validator} via {@code @ParameterizedClass} — the class is
 * invoked once per parameter value, each invocation acting like a separate
 * class execution. This tests C2/C8 from the JUnit 6 compatibility audit:
 * <ul>
 *   <li>C2: TelemetryListener sees N ClassSource events for one ClassDescriptor</li>
 *   <li>C8: @Nested inside @ParameterizedClass creates combinatorial invocations</li>
 * </ul>
 */
@ParameterizedClass
@ValueSource(strings = {"Alice", "Bob", ""})
class ValidatorParameterizedClassTest {

    private final String name;

    ValidatorParameterizedClassTest(String name) {
        this.name = name;
    }

    @Test
    void nameValidation() {
        Validator v = new Validator();
        if (name.isBlank()) {
            assertFalse(v.isValidName(name), "Blank name should be invalid");
        } else {
            assertTrue(v.isValidName(name), "'" + name + "' should be valid");
        }
    }

    @Test
    void nullNameIsInvalid() {
        assertFalse(new Validator().isValidName(null));
    }

    /**
     * C8: @Nested inside @ParameterizedClass — each nested test runs for
     * every parameter of the enclosing class (3 names × 2 tests = 6 executions).
     */
    @Nested
    class AgeValidation {

        @Test
        void validAge() {
            assertTrue(new Validator().isValidAge(25),
                    "Age 25 should be valid for name context: " + name);
        }

        @Test
        void negativeAgeIsInvalid() {
            assertFalse(new Validator().isValidAge(-1));
        }
    }
}
