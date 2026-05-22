package com.example.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * JUnit 4 test for UserController (web module, depends on core).
 */
public class UserControllerTest {

    private final UserController controller = new UserController();

    @Test
    public void testGetUser() {
        assertEquals("User-42", controller.getUser("42"));
    }

    @Test
    public void testGetUserInvalidId() {
        assertEquals("ERROR: invalid id", controller.getUser(null));
        assertEquals("ERROR: invalid id", controller.getUser(""));
    }
}
