package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InnerClassTest {

    @Test
    void testOuter() {
        assertEquals(1, 1);
    }

    public class InnerTest {
        @Test
        void testInner() {
            assertEquals(2, 2);
        }
    }
}
