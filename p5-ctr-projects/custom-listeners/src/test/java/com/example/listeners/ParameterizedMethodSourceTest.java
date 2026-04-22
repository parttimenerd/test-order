package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Assertions;
import java.util.stream.Stream;

/**
 * Test class with method source parameterized tests.
 */
@DisplayName("Parameterized Tests - Method Source")
public class ParameterizedMethodSourceTest {

    static Stream<Integer> provideNumbers() {
        return Stream.of(1, 2, 3, 4, 5);
    }

    static Stream<String> provideStrings() {
        return Stream.of("hello", "world", "test");
    }

    @ParameterizedTest
    @MethodSource("provideNumbers")
    @DisplayName("Test with method source - numbers")
    void testWithMethodSourceNumbers(int number) {
        Assertions.assertTrue(number > 0);
    }

    @ParameterizedTest
    @MethodSource("provideStrings")
    @DisplayName("Test with method source - strings")
    void testWithMethodSourceStrings(String str) {
        Assertions.assertFalse(str.isEmpty());
    }

    @Test
    @DisplayName("Regular test in method source class")
    void regularTest() {
        Assertions.assertTrue(true);
    }
}
