package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MixedMethodTest {

    @Test
    void testOne() {
        assertEquals(1, 1);
    }

    public void helperMethod() {
        // Not a test
        return;
    }

    @Test
    void testTwo() {
        assertTrue(true);
    }

    private String nonTestMethod() {
        return "helper";
    }

    @Test
    void testThree() {
        assertEquals("helper", nonTestMethod());
    }
}
