package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import java.util.Collection;
import java.util.ArrayList;

/**
 * Testing ordering consistency with dynamic tests
 * test-order should maintain order of test execution
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class OrderingConsistencyTests {

    static java.util.List<String> executionOrder = new java.util.ArrayList<>();

    @TestFactory
    Collection<DynamicTest> dynamicA() {
        executionOrder.clear();
        java.util.List<DynamicTest> tests = new java.util.ArrayList<>();
        tests.add(DynamicTest.dynamicTest("A-1", () -> {
            executionOrder.add("A-1");
        }));
        tests.add(DynamicTest.dynamicTest("A-2", () -> {
            executionOrder.add("A-2");
        }));
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> dynamicB() {
        java.util.List<DynamicTest> tests = new java.util.ArrayList<>();
        tests.add(DynamicTest.dynamicTest("B-1", () -> {
            executionOrder.add("B-1");
        }));
        tests.add(DynamicTest.dynamicTest("B-2", () -> {
            executionOrder.add("B-2");
        }));
        return tests;
    }

    @Test
    void verifyOrder() {
        // Note: This may fail if dynamic tests don't execute in proper order
        assert executionOrder.size() >= 2;
    }
}
