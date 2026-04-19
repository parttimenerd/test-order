package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Formatter only — no transitive deps. */
class FormatterTest {

    private final Formatter formatter = new Formatter();

    @Test
    void testToUpperCase() {
        assertEquals("HELLO", formatter.toUpperCase("hello"));
    }

    @Test
    void testToUpperCaseNull() {
        assertNull(formatter.toUpperCase(null));
    }

    @Test
    void testWrap() {
        assertEquals("[hello]", formatter.wrap("hello", "[", "]"));
    }

    @Test
    void testRepeat() {
        assertEquals("abcabc", formatter.repeat("abc", 2));
    }
}
