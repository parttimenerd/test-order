package com.example;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 test class
 */
public class JUnit4LegacyTest {
    @Test
    public void testLegacyOne() {
        assertTrue(true);
    }

    @Test
    public void testLegacyTwo() {
        assertEquals(1, 1);
    }
}
