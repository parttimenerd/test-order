package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConstructorSideEffectTest {

    private static int constructorCallCount = 0;

    public ConstructorSideEffectTest() {
        constructorCallCount++;
        System.out.println("Constructor called, count=" + constructorCallCount);
    }

    @Test
    void testOne() {
        assertEquals(1, 1);
    }

    @Test
    void testTwo() {
        assertEquals(2, 2);
    }

    @Test
    void testThree() {
        assertEquals(3, 3);
    }
}
