package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class with @RepeatedTest annotation.
 * Tests if test-order correctly counts repeated tests.
 */
@DisplayName("Repeated Tests")
public class RepeatedTestsTest {
    @RepeatedTest(3)
    @DisplayName("Repeated 3 times")
    void repeatedTest(int repetition) {
        assertTrue(repetition >= 1 && repetition <= 3);
    }

    @Test
    @DisplayName("Regular test alongside repeated test")
    void regularTest() {
        assertEquals(1, 1);
    }
}
