package com.example.advanced;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Collection;
import java.util.Arrays;

/**
 * Pattern: HIGH VOLUME STRESS TEST
 * Testing with 100+ dynamic tests, repeated tests, and parameterized tests
 */
class HighVolumeStressTests {

    // 100 dynamic tests
    @TestFactory
    Collection<DynamicTest> hundredDynamicTests() {
        java.util.List<DynamicTest> tests = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int num = i;
            tests.add(DynamicTest.dynamicTest("dynamic-" + i, () -> {
                assert num >= 0 && num < 100;
            }));
        }
        return tests;
    }

    // 50 repeated tests
    @RepeatedTest(50)
    void stressRepeatedTest() {
        assert true;
    }

    // 20 parameterized with values
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void stressParameterizedTest(int value) {
        assert value > 0 && value <= 20;
    }

    // Total expected: 100 + 50 + 20 = 170 tests
}
