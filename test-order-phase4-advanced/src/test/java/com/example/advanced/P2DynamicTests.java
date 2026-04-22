package com.example.advanced;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.Collection;
import java.util.Arrays;

/**
 * Pattern 2: Dynamic Tests
 * Testing @TestFactory creating tests at runtime
 * Nested TestFactory methods
 */
class P2DynamicTests {

    // Should create 5 dynamic tests
    @TestFactory
    Collection<DynamicTest> dynamicTestsFromCollection() {
        return Arrays.asList(
            DynamicTest.dynamicTest("1st dynamic test", () -> { assert true; }),
            DynamicTest.dynamicTest("2nd dynamic test", () -> { assert true; }),
            DynamicTest.dynamicTest("3rd dynamic test", () -> { assert true; }),
            DynamicTest.dynamicTest("4th dynamic test", () -> { assert true; }),
            DynamicTest.dynamicTest("5th dynamic test", () -> { assert true; })
        );
    }

    // Should create 3 dynamic tests
    @TestFactory
    Collection<DynamicTest> dynamicNestedTests() {
        return Arrays.asList(
            DynamicTest.dynamicTest("nested-1", () -> { assert 1 + 1 == 2; }),
            DynamicTest.dynamicTest("nested-2", () -> { assert 2 + 2 == 4; }),
            DynamicTest.dynamicTest("nested-3", () -> { assert 3 + 3 == 6; })
        );
    }

    // Should create 10 dynamic tests (high volume test)
    @TestFactory
    Collection<DynamicTest> manyDynamicTests() {
        java.util.List<DynamicTest> tests = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int num = i;
            tests.add(DynamicTest.dynamicTest("test-" + i, () -> { assert num >= 0; }));
        }
        return tests;
    }

    // Total expected: 5 + 3 + 10 = 18 dynamic tests
}
