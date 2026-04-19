package com.myapp.service;

import com.myapp.model.User;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {
    private final UserService service = new UserService();

    @Test
    void testCreateValidUser() {
        User user = service.createUser("Alice", "alice@example.com", 25);
        assertEquals("Alice", user.getName());
    }

    @Test
    void testCreateUserInvalidEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createUser("Alice", "not-an-email", 25));
    }

    @Test
    void testCreateUserBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createUser("", "alice@test.com", 25));
    }

    @Test
    void testCanPurchase() {
        User adult = new User("Adult", "a@b.com", 21);
        User minor = new User("Minor", "m@b.com", 15);
        assertTrue(service.canPurchase(adult));
        assertFalse(service.canPurchase(minor));
    }
}
