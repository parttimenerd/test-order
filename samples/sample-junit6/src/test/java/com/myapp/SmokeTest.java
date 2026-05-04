package com.myapp;

import me.bechberger.testorder.AlwaysRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test marked {@code @AlwaysRun}: guaranteed to run in every
 * {@code select}/{@code auto} invocation — never deferred to
 * the remaining-tests phase.
 */
@AlwaysRun
class SmokeTest {

    @Test
    void greeterNotNull() {
        assertNotNull(new Greeter().greet("smoke"));
    }

    @Test
    void mathServiceBasicAdd() {
        assertEquals(2, new MathService().add(1, 1));
    }

    @Test
    void validatorAcceptsSimpleName() {
        assertTrue(new Validator().isValidName("smoke"));
    }
}
