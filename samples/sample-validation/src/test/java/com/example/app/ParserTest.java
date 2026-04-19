package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Parser only — no transitive deps. */
class ParserTest {

    private final Parser parser = new Parser();

    @Test
    void testParseInts() {
        assertArrayEquals(new int[]{1, 2, 3}, parser.parseInts("1, 2, 3"));
    }

    @Test
    void testParseIntsEmpty() {
        assertArrayEquals(new int[0], parser.parseInts(""));
    }

    @Test
    void testFormatInts() {
        assertEquals("1, 2, 3", parser.formatInts(new int[]{1, 2, 3}));
    }

    @Test
    void testRoundTrip() {
        String input = "10, 20, 30";
        assertEquals(input, parser.formatInts(parser.parseInts(input)));
    }
}
