package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LongMethodNameTest {

    @Test
    void thisIsAVeryVeryVeryVeryVeryVeryVeryVeryVeryVeryLongTestMethodNameThatShouldStillWorkProperly() {
        assertEquals(1, 1);
    }

    @Test
    void anotherTestWithAVeryLongNameThatContainsManyWordsAndShouldBeHandledCorrectlyByTestOrder123456789() {
        assertTrue(true);
    }
}
