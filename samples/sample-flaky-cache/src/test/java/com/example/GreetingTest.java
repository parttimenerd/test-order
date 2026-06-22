package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreetingTest {
    @Test
    void hello() {
        assertEquals("Hello, world!", new Greeting().hello("world"));
    }
}
