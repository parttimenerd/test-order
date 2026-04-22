package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Assertions;
import java.util.stream.Stream;

/**
 * Test class with various parameterized tests.
 * Tests if test-order correctly handles parameterized test counting.
 */
@DisplayName("Parameterized Tests - Value Source")
public class ParameterizedValueTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("Test with int value source")
    void testWithIntValues(int value) {
        Assertions.assertTrue(value > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apple", "banana", "cherry"})
    @DisplayName("Test with string value source")
    void testWithStringValues(String value) {
        Assertions.assertFalse(value.isEmpty());
    }

    @Test
    @DisplayName("Regular test in parameterized class")
    void regularTest() {
        Assertions.assertTrue(true);
    }
}
