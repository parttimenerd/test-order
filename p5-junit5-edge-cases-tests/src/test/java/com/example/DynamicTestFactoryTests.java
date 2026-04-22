package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Tests for @TestFactory dynamic tests.
 * Tests edge cases in dynamic test generation and test-order interaction.
 */
@DisplayName("Dynamic Test Factory Tests")
public class DynamicTestFactoryTests {

    @TestFactory
    @DisplayName("Dynamic Test Factory 1: Basic dynamic tests")
    Collection<DynamicTest> test01_basicDynamicTests() {
        Collection<DynamicTest> tests = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int num = i;
            tests.add(dynamicTest(
                    "Dynamic Test 1." + i,
                    () -> { if (!(num > 0)) throw new AssertionError("num > 0"); }
            ));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 2: String-based dynamic tests")
    Collection<DynamicTest> test02_stringBasedDynamic() {
        String[] inputs = {"alpha", "beta", "gamma"};
        Collection<DynamicTest> tests = new ArrayList<>();
        for (String input : inputs) {
            tests.add(dynamicTest(
                    "Test for: " + input,
                    () -> { if (!(input != null && !input.isEmpty())) throw new AssertionError("input not null and not empty"); }
            ));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 3: Parameterized dynamic tests")
    Collection<DynamicTest> test03_parameterizedDynamic() {
        int[][] testData = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        Collection<DynamicTest> tests = new ArrayList<>();
        for (int[] data : testData) {
            int first = data[0];
            tests.add(dynamicTest(
                    "Sum test for " + first,
                    () -> { int sum = first + data[1] + data[2]; if (!(sum > 0)) throw new AssertionError("sum > 0"); }
            ));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 4: Conditional dynamic tests")
    Collection<DynamicTest> test04_conditionalDynamic() {
        Collection<DynamicTest> tests = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            int num = i;
            if (num % 2 == 0) {
                tests.add(dynamicTest(
                        "Even number: " + num,
                        () -> { if (!(num % 2 == 0)) throw new AssertionError("num % 2 == 0"); }
                ));
            } else {
                tests.add(dynamicTest(
                        "Odd number: " + num,
                        () -> { if (!(num % 2 != 0)) throw new AssertionError("num % 2 != 0"); }
                ));
            }
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 5: Empty dynamic tests")
    Collection<DynamicTest> test05_emptyDynamic() {
        return new ArrayList<>(); // No dynamic tests
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 6: Single dynamic test")
    Collection<DynamicTest> test06_singleDynamic() {
        Collection<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("Single Dynamic Test", () -> {}));
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 7: Many dynamic tests")
    Collection<DynamicTest> test07_manyDynamic() {
        Collection<DynamicTest> tests = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            int num = i;
            tests.add(dynamicTest(
                    "Test " + i,
                    () -> { if (!(num > 0 && num <= 10)) throw new AssertionError("num in range"); }
            ));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 8: With Unicode in names")
    Collection<DynamicTest> test08_unicodeDynamic() {
        Collection<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("Greek: α", () -> {}));
        tests.add(dynamicTest("Japanese: 日本", () -> {}));
        tests.add(dynamicTest("Emoji: 🎯", () -> {}));
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 9: Nested structure")
    Collection<DynamicTest> test09_nestedStructure() {
        Collection<DynamicTest> tests = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int outer = i;
            StringBuilder displayName = new StringBuilder();
            displayName.append("Nested ").append(outer).append(": ");
            tests.add(dynamicTest(
                    displayName.toString(),
                    () -> {
                        for (int j = 1; j <= 2; j++) {
                            if (!(outer > 0 && j > 0)) throw new AssertionError("outer > 0 && j > 0");
                        }
                    }
            ));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Dynamic Test Factory 10: Complex test cases")
    Collection<DynamicTest> test10_complexCases() {
        Collection<DynamicTest> tests = new ArrayList<>();
        String[] testCases = {"case1", "case2", "case3", "case4", "case5"};
        for (String testCase : testCases) {
            tests.add(dynamicTest(
                    "Complex: " + testCase,
                    () -> {
                        if (testCase == null) throw new AssertionError("testCase != null");
                        if (testCase.length() == 0) throw new AssertionError("testCase.length() > 0");
                        if (!testCase.startsWith("case")) throw new AssertionError("testCase.startsWith(\"case\")");
                    }
            ));
        }
        return tests;
    }
}
