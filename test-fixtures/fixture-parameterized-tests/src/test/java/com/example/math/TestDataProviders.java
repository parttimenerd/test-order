package com.example.math;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

/**
 * Test data provider utility class. Changes to this class should be tracked
 * as test utility dependencies — tests calling methods from this class should
 * get a depOverlap score boost when this class changes.
 */
public class TestDataProviders {

    /**
     * Provides prime number test data.
     */
    public static Stream<Arguments> primeTestData() {
        return Stream.of(
                Arguments.of(2, true),
                Arguments.of(3, true),
                Arguments.of(4, false),
                Arguments.of(17, true),
                Arguments.of(20, false)
        );
    }

    /**
     * Provides division test data.
     */
    public static Stream<Arguments> divisionTestData() {
        return Stream.of(
                Arguments.of(10, 2, 5),
                Arguments.of(9, 3, 3),
                Arguments.of(100, 10, 10)
        );
    }
}
