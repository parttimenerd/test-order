package com.example;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

/**
 * Simple JUnit 4 test class with setUp/tearDown hooks.
 */
public class JUnit4SimpleTest {
    private StringBuilder stringBuilder;

    @Before
    public void setUp() {
        stringBuilder = new StringBuilder();
    }

    @After
    public void tearDown() {
        stringBuilder = null;
    }

    @Test
    public void testAppendString() {
        stringBuilder.append("Hello");
        assertEquals("Hello", stringBuilder.toString());
    }

    @Test
    public void testClear() {
        stringBuilder.append("test");
        stringBuilder.setLength(0);
        assertEquals("", stringBuilder.toString());
    }

    @Test
    public void testLength() {
        stringBuilder.append("1234");
        assertEquals(4, stringBuilder.length());
    }
}
