package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import static org.junit.jupiter.api.Assertions.*;

public class SkippedTestClass {
    @Test
    void normalTest() {
        assertTrue(true);
    }

    @Test
    @Disabled("Not ready yet")
    void skippedTest() {
        fail("Should be skipped");
    }

    @Test
    @Disabled
    void anotherSkipped() {
        assertTrue(false);
    }
}
