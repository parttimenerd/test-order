package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BothTest {
    @Test
    public void testCalcAndString() {
        Calculator c = new Calculator();
        StringHelper s = new StringHelper();
        assertEquals("5", s.reverse(String.valueOf(c.add(2, 3))));
    }
}
