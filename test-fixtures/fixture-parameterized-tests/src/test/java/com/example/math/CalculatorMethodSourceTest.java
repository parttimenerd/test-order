package com.example.math;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests using @MethodSource to verify that provider method changes are detected.
 */
public class CalculatorMethodSourceTest {

    /**
     * Provider method for addition test data. If this method's body changes,
     * the test method hash should also change (even though the test body itself
     * didn't change).
     */
    static Stream<Arguments> provideAdditionData() {
        return Stream.of(
                Arguments.of(1, 1, 2),
                Arguments.of(2, 3, 5),
                Arguments.of(100, 200, 300)
        );
    }

    @ParameterizedTest
    @MethodSource("provideAdditionData")
    void testAddWithMethodSource(int a, int b, int expected) {
        assertEquals(expected, Calculator.add(a, b));
    }

    /**
     * Provider method that delegates to a utility class. Changes to
     * TestDataProviders should be tracked via the agent instrumentation.
     */
    static Stream<Arguments> providePrimeData() {
        return TestDataProviders.primeTestData();
    }

    @ParameterizedTest
    @MethodSource("providePrimeData")
    void testIsPrimeWithMethodSource(int number, boolean expectedPrime) {
        assertEquals(expectedPrime, Calculator.isPrime(number));
    }

    /**
     * Test using @MethodSource with no explicit value (defaults to method name).
     */
    static Stream<Arguments> testMultiplicationWithImplicitSource() {
        return Stream.of(
                Arguments.of(3, 4, 12),
                Arguments.of(7, 8, 56)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testMultiplicationWithImplicitSource(int a, int b, int expected) {
        assertEquals(expected, Calculator.multiply(a, b));
    }
}
