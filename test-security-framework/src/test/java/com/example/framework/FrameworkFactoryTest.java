package com.example.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

import java.util.Collection;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.*;

/**
 * JUnit 5 Framework Test Suite for P5-TFEC-004: @TestFactory Edge Cases
 * 
 * Tests edge cases with @TestFactory annotation for dynamic test generation:
 * 1. Empty test collections
 * 2. Single test from factory
 * 3. Multiple tests from factory
 * 4. Invalid/null returns
 * 5. Exception handling in factories
 * 6. Stream-based factories
 * 7. Collection-based factories
 */
@DisplayName("Framework - @TestFactory Dynamic Tests (P5-TFEC-004)")
class FrameworkFactoryTest {

    @TestFactory
    @DisplayName("P5-TFEC-004: Basic Factory - Generate simple dynamic tests")
    Collection<DynamicTest> testBasicFactory() {
        return java.util.Arrays.asList(
            dynamicTest("First dynamic test", () -> assertTrue(true)),
            dynamicTest("Second dynamic test", () -> assertEquals(1 + 1, 2)),
            dynamicTest("Third dynamic test", () -> assertNotNull("data"))
        );
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Stream Factory - Generate tests from stream")
    Stream<DynamicTest> testStreamFactory() {
        return Stream.of(1, 2, 3)
            .map(i -> dynamicTest("Test for " + i, () -> assertTrue(i > 0)));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Single Dynamic Test - Factory returning one test")
    Collection<DynamicTest> testSingleDynamicTest() {
        return java.util.Collections.singleton(
            dynamicTest("Single test", () -> assertTrue(true))
        );
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Generated Test Names - Display name from factory")
    Stream<DynamicTest> testGeneratedNames() {
        return Stream.of("alpha", "beta", "gamma")
            .map(name -> dynamicTest(
                "Test with " + name,
                () -> assertNotNull(name)
            ));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Parameterized Dynamic Tests - Multiple parameters")
    Stream<DynamicTest> testParameterizedDynamic() {
        return Stream.of(
            new int[]{1, 2, 3},
            new int[]{4, 5, 9},
            new int[]{10, 20, 30}
        ).map(arr -> dynamicTest(
            arr[0] + " + " + arr[1] + " = " + arr[2],
            () -> assertEquals(arr[0] + arr[1], arr[2])
        ));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Iterable Factory - Generate from iterable")
    Iterable<DynamicTest> testIterableFactory() {
        java.util.List<Integer> numbers = java.util.Arrays.asList(1, 2, 3, 4, 5);
        return numbers.stream()
            .map(n -> dynamicTest(
                "Is " + n + " positive?",
                () -> assertTrue(n > 0)
            ))
            .toList();
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Factory with Assertions - Various assertion types")
    Stream<DynamicTest> testFactoryWithVariousAssertions() {
        return Stream.of(
            dynamicTest("True assertion", () -> assertTrue(true)),
            dynamicTest("False assertion", () -> assertFalse(false)),
            dynamicTest("Equals assertion", () -> assertEquals(2, 1 + 1)),
            dynamicTest("Null assertion", () -> assertNull(null)),
            dynamicTest("Not null assertion", () -> assertNotNull("not null"))
        );
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Factory with Exception Expected")
    Stream<DynamicTest> testFactoryWithExceptions() {
        return Stream.of(
            dynamicTest("Division by zero", () -> 
                assertThrows(ArithmeticException.class, () -> { int x = 1 / 0; })
            ),
            dynamicTest("Null pointer", () -> 
                assertThrows(NullPointerException.class, () -> {
                    String s = null;
                    s.length();
                })
            )
        );
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Large Factory - Generate many tests")
    Stream<DynamicTest> testLargeFactory() {
        return java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> dynamicTest(
                "Test #" + i,
                () -> assertTrue(i >= 0 && i < 100)
            ));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Nested Display Names - Hierarchical test names")
    Collection<DynamicTest> testNestedDisplayNames() {
        return java.util.Arrays.asList(
            dynamicTest("[Category A] Test 1", () -> assertTrue(true)),
            dynamicTest("[Category A] Test 2", () -> assertTrue(true)),
            dynamicTest("[Category B] Test 1", () -> assertTrue(true)),
            dynamicTest("[Category B] Test 2", () -> assertTrue(true))
        );
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Dynamic Test Filtering")
    Stream<DynamicTest> testDynamicFiltering() {
        return Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            .filter(i -> i % 2 == 0)  // Only even numbers
            .map(i -> dynamicTest(
                "Even number: " + i,
                () -> assertEquals(0, i % 2)
            ));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Complex Object Factory")
    Stream<DynamicTest> testComplexObjectFactory() {
        record TestData(String name, int value, boolean expected) {}
        
        return Stream.of(
            new TestData("Positive", 5, true),
            new TestData("Zero", 0, false),
            new TestData("Negative", -3, false)
        ).map(data -> dynamicTest(
            data.name + ": " + data.value,
            () -> assertEquals(data.expected, data.value > 0)
        ));
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Empty Factory - Edge case with no tests")
    Collection<DynamicTest> testEmptyFactory() {
        // This factory returns an empty collection
        return java.util.Collections.emptyList();
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Factory with State")
    Stream<DynamicTest> testFactoryWithState() {
        int[] counter = {0};
        
        return Stream.of(1, 2, 3)
            .map(i -> dynamicTest(
                "Test " + i,
                () -> {
                    counter[0]++;
                    assertTrue(counter[0] <= 3);
                }
            ));
    }

    @Test
    @DisplayName("P5-TFEC-004: Regular Test vs Factory - Regular tests still work")
    void testRegularTestStillWorks() {
        assertTrue(true, "Regular @Test methods should still execute");
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Lambdas in Factory - Use lambdas for test logic")
    Collection<DynamicTest> testLambdasInFactory() {
        java.util.List<String> inputs = java.util.Arrays.asList("a", "b", "c");
        
        return inputs.stream()
            .map(input -> dynamicTest(
                "Lambda test: " + input,
                () -> assertFalse(input.isEmpty())
            ))
            .toList();
    }

    @TestFactory
    @DisplayName("P5-TFEC-004: Factory with Execution Container")
    Stream<DynamicTest> testFactoryWithContainer() {
        return Stream.of("one", "two", "three")
            .map(word -> dynamicTest(
                word.toUpperCase(),
                () -> assertEquals(3, word.length())
            ));
    }
}
