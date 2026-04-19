package com.myapp.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTest {
    @Test
    void testCreateUser() {
        User user = new User("Alice", "alice@example.com", 30);
        assertEquals("Alice", user.getName());
        assertEquals("alice@example.com", user.getEmail());
        assertEquals(30, user.getAge());
    }

    @Test
    void testIsAdult() {
        assertTrue(new User("Bob", "bob@test.com", 18).isAdult());
        assertFalse(new User("Kid", "kid@test.com", 17).isAdult());
    }

    @Test
    void testToString() {
        User user = new User("Alice", "alice@example.com", 30);
        assertEquals("Alice <alice@example.com>", user.toString());
    }
}
