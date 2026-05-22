package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ManyAssertionsTest {

    @Test
    void testWithManyAssertions() {
        for (int i = 0; i < 100; i++) {
            assertEquals(i, i, "Assertion " + i);
        }
    }

    @Test
    void testNormal() {
        assertEquals(1, 1);
    }
}
