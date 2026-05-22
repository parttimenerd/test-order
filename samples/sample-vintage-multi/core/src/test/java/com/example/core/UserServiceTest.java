package com.example.core;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JUnit 4 test for UserService (core module).
 */
public class UserServiceTest {

    private final UserService service = new UserService();

    @Test
    public void testFindUser() {
        assertEquals("User-123", service.findUser("123"));
    }

    @Test
    public void testIsValidId() {
        assertTrue(service.isValidId("abc"));
        assertFalse(service.isValidId(null));
        assertFalse(service.isValidId(""));
    }
}
