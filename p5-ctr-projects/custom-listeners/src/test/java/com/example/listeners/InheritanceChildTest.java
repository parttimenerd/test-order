package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assertions;

/**
 * Test class that extends base test class.
 * Tests if test-order correctly handles inherited test methods.
 */
@DisplayName("Inheritance Child Test")
public class InheritanceChildTest extends InheritanceBaseTest {

    @Test
    @DisplayName("Child class test 1")
    void childTest1() {
        Assertions.assertTrue(true);
    }

    @Test
    @DisplayName("Child class test 2")
    void childTest2() {
        Assertions.assertEquals(2, 2);
    }

    @Override
    @Test
    @DisplayName("Overridden parent test")
    void baseTest1() {
        Assertions.assertTrue(true);
    }
}
