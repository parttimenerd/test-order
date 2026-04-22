package com.example;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collection;

/**
 * JUnit 4 parameterized test class.
 * Tests if test-order correctly counts parameterized test instances.
 */
@RunWith(Parameterized.class)
public class JUnit4ParameterizedTest {
    private int input;
    private int expected;

    @Parameterized.Parameters(name = "Test {index}: {0} + {0} = {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            { 1, 2 },
            { 2, 4 },
            { 3, 6 },
            { 5, 10 }
        });
    }

    public JUnit4ParameterizedTest(int input, int expected) {
        this.input = input;
        this.expected = expected;
    }

    @Test
    public void testDoubleOfNumber() {
        assertEquals(expected, input + input);
    }

    @Test
    public void testIsPositive() {
        assertTrue(input > 0);
    }
}
