package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assertions;

/**
 * Test class with inheritance - base class with test methods.
 */
@DisplayName("Inheritance Base Test")
public class InheritanceBaseTest {

    @Test
    @DisplayName("Base class test 1")
    void baseTest1() {
        Assertions.assertTrue(true);
    }

    @Test
    @DisplayName("Base class test 2")
    void baseTest2() {
        Assertions.assertEquals(1, 1);
    }
}
