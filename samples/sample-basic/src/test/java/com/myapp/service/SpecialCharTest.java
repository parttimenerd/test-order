package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

public class SpecialCharTest {

    @Test
    @DisplayName("Test with special chars: !@#$%^&*()")
    void testSpecial() {
        assertEquals(1, 1);
    }

    @Test
    @DisplayName("Test with unicode: 你好世界 🎉")
    void testUnicode() {
        assertEquals(1, 1);
    }

    @Test
    void normalTest() {
        assertEquals(1, 1);
    }
}
