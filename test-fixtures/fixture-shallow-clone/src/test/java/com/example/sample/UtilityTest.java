package com.example.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UtilityTest {

    @Test
    public void testGreet() {
        assertEquals("Hello, World!", Utility.greet("World"));
    }

    @Test
    public void testSquare() {
        assertEquals(9, Utility.square(3));
        assertEquals(0, Utility.square(0));
        assertEquals(1, Utility.square(-1));
    }
}
