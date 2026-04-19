package com.myapp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests Greeter directly. */
class GreeterTest {

    @Test
    void greetReturnsExpected() {
        var g = new Greeter();
        assertEquals("Hello, World!", g.greet("World"));
    }

    @Test
    void greetWithEmptyName() {
        var g = new Greeter();
        assertEquals("Hello, !", g.greet(""));
    }
}
