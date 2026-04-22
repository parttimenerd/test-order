package com.example.advanced;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Pattern 1: JUnit 5 Parameterized Tests
 * Testing @ParameterizedTest with various sources:
 * - @ValueSource
 * - @CsvSource
 * - @MethodSource
 * - Custom ArgumentsProvider
 */
class P1ParameterizedTests {

    // Should count as 5 tests (one per value)
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void testWithIntValues(int value) {
        assert value > 0;
    }

    // Should count as 3 tests (one per string)
    @ParameterizedTest
    @ValueSource(strings = {"apple", "banana", "cherry"})
    void testWithStringValues(String value) {
        assert !value.isEmpty();
    }

    // Should count as 4 tests (one per CSV line)
    @ParameterizedTest
    @CsvSource({
        "1,2,3",
        "2,4,6",
        "3,6,9",
        "4,8,12"
    })
    void testWithCsvSource(int a, int b, int expected) {
        assert a * 2 == b;
    }

    // Should count as 3 tests from method source
    @ParameterizedTest
    @MethodSource("provideNumbers")
    void testWithMethodSource(int number) {
        assert number > 0;
    }

    static java.util.stream.Stream<Integer> provideNumbers() {
        return java.util.stream.Stream.of(10, 20, 30);
    }

    // Custom ArgumentsProvider: should count as 2 tests
    @ParameterizedTest
    @ArgumentsSource(CustomArgumentsProvider.class)
    void testWithCustomProvider(String arg) {
        assert arg != null;
    }

    static class CustomArgumentsProvider implements org.junit.jupiter.params.provider.ArgumentsProvider {
        @Override
        public java.util.stream.Stream<? extends org.junit.jupiter.params.provider.Arguments> provideArguments(
                org.junit.jupiter.api.extension.ExtensionContext context) {
            return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("arg1"),
                org.junit.jupiter.params.provider.Arguments.of("arg2")
            );
        }
    }

    // Total expected: 5 + 3 + 4 + 3 + 2 = 17 tests
}
