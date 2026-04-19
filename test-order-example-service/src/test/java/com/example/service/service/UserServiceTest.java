package com.example.service.service;

import com.example.service.model.User;
import com.example.service.repo.UserRepository;
import com.example.service.validation.UserValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    private UserService service;
    private UserRepository repo;

    @BeforeEach
    void setUp() {
        repo = new UserRepository();
        service = new UserService(repo, new UserValidator());
    }

    @Test
    void registerValidUser() {
        User user = service.register("Alice", "alice@example.com");
        assertEquals("Alice", user.getName());
        assertEquals("alice@example.com", user.getEmail());
        assertEquals(1, repo.count());
    }

    @Test
    void registerInvalidUserThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register("", "alice@example.com"));
    }

    @Test
    void registerDuplicateEmailThrows() {
        service.register("Alice", "alice@example.com");
        assertThrows(IllegalStateException.class,
                () -> service.register("Alice2", "alice@example.com"));
    }

    @Test
    void findByEmail() {
        service.register("Alice", "alice@example.com");
        assertTrue(service.findByEmail("alice@example.com").isPresent());
        assertTrue(service.findByEmail("nobody@example.com").isEmpty());
    }

    @Test
    void listAll() {
        service.register("Alice", "alice@example.com");
        service.register("Bob", "bob@example.com");
        assertEquals(2, service.listAll().size());
    }
}
