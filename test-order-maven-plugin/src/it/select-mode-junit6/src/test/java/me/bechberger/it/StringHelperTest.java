package me.bechberger.it;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringHelperTest {
    @Test
    void testUpper() {
        assertEquals("HELLO", StringHelper.upper("hello"));
    }
}
