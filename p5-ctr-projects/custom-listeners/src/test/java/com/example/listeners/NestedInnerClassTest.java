package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;

/**
 * Test class with nested inner test classes.
 * Tests if test-order correctly discovers and counts nested tests.
 */
@DisplayName("Outer Test Class")
public class NestedInnerClassTest {

    @Test
    @DisplayName("Outer class test")
    void outerTest() {
        Assertions.assertTrue(true);
    }

    @Nested
    @DisplayName("Nested Inner Class 1")
    class InnerClass1 {
        @Test
        @DisplayName("Inner test 1")
        void innerTest1() {
            Assertions.assertTrue(true);
        }

        @Test
        @DisplayName("Inner test 2")
        void innerTest2() {
            Assertions.assertEquals(1, 1);
        }
    }

    @Nested
    @DisplayName("Nested Inner Class 2")
    class InnerClass2 {
        @Test
        @DisplayName("Inner test 3")
        void innerTest3() {
            Assertions.assertTrue(true);
        }

        @Nested
        @DisplayName("Doubly Nested Class")
        class DoublyNested {
            @Test
            @DisplayName("Doubly nested test")
            void doublyNestedTest() {
                Assertions.assertTrue(true);
            }
        }
    }
}
