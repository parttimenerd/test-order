package com.myapp.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserControllerTest {
    @Test
    void getUser() {
        assertEquals("Found: User-1", new UserController().getUser(1));
    }
}
