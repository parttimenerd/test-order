package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test class
 */
public class JUnit5ModernTest {
    @Test
    void testModernOne() {
        assertTrue(true);
    }

    @Test
    void testModernTwo() {
        assertEquals(2, 2);
    }
}
