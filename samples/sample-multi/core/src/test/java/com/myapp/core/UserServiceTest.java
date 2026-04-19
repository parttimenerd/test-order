package com.myapp.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {
    @Test
    void lookupName() {
        assertEquals("User-42", new UserService().lookupName(42));
    }
}
