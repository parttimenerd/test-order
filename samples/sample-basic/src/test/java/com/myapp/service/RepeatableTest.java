package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;

public class RepeatableTest {

    @RepeatedTest(3)
    void repeatedTest() {
        assertEquals(1, 1);
    }

    @Test
    void normalTest() {
        assertTrue(true);
    }
}
