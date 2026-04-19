package com.myapp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests MessageFormatter → depends on Greeter transitively. */
class MessageFormatterTest {

    @Test
    void welcomeContainsGreeting() {
        var fmt = new MessageFormatter(new Greeter());
        String result = fmt.welcome("Alice");
        assertTrue(result.contains("Hello, Alice!"));
        assertTrue(result.contains("Welcome aboard!"));
    }

    @Test
    void farewellMessage() {
        var fmt = new MessageFormatter(new Greeter());
        assertEquals("Goodbye, Bob!", fmt.farewell("Bob"));
    }
}
