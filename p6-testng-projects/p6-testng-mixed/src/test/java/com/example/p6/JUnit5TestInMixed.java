package com.example.p6;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P6-TESTNG-005: JUnit 5 tests in mixed project
 */
public class JUnit5TestInMixed {
    private TestUtils utils;

    @BeforeEach
    public void setUp() {
        utils = new TestUtils();
    }

    @AfterEach
    public void tearDown() {
        utils = null;
    }

    @Test
    public void junit5_testAddition() {
        int result = utils.add(2, 3);
        assertEquals(5, result);
    }

    @Test
    public void junit5_testStringReverse() {
        String result = utils.reverse("abc");
        assertEquals("cba", result);
    }

    @Test
    public void junit5_testEmpty() {
        boolean empty = utils.isEmpty("");
        assertTrue(empty);
    }
}
