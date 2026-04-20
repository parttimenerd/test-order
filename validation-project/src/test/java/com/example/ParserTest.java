package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ParserTest {
    private Parser parser = new Parser();

    @Test
    public void testParseNumbers() {
        int[] result = parser.parseNumbers("1, 2, 3, 4, 5");
        assertArrayEquals(new int[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    public void testParseNumbersWithoutSpaces() {
        int[] result = parser.parseNumbers("10,20,30");
        assertArrayEquals(new int[]{10, 20, 30}, result);
    }

    @Test
    public void testFormatNumbers() {
        String result = parser.formatNumbers(new int[]{1, 2, 3});
        assertEquals("1, 2, 3", result);
    }

    @Test
    public void testFormatSingleNumber() {
        String result = parser.formatNumbers(new int[]{42});
        assertEquals("42", result);
    }

    @Test
    public void testRoundTrip() {
        String input = "5, 10, 15, 20";
        int[] parsed = parser.parseNumbers(input);
        String formatted = parser.formatNumbers(parsed);
        assertEquals("5, 10, 15, 20", formatted);
    }
}
