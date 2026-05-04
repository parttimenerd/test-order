package com.example.fields;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests UserRegistry - depends on UserRegistry fields and methods. Uses field
 * access to verify state.
 */
class UserRegistryTest {

	private UserRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new UserRegistry();
	}

	@Test
	void testRegisterUser() {
		registry.registerUser("alice", "alice@example.com");
		assertEquals(1, registry.getUserCount());
		assertTrue(registry.containsUser("alice"));
	}

	@Test
	void testGetEmail() {
		registry.registerUser("bob", "bob@example.com");
		assertEquals("bob@example.com", registry.getEmail("bob"));
	}

	@Test
	void testSetAndGetScore() {
		registry.registerUser("charlie", "charlie@example.com");
		registry.setScore("charlie", 100);
		assertEquals(100, registry.getScore("charlie"));
	}

	@Test
	void testMultipleUsers() {
		registry.registerUser("user1", "user1@example.com");
		registry.registerUser("user2", "user2@example.com");
		registry.registerUser("user3", "user3@example.com");
		assertEquals(3, registry.getUserCount());
	}

	@Test
	void testRemoveUser() {
		registry.registerUser("dave", "dave@example.com");
		assertTrue(registry.containsUser("dave"));
		registry.removeUser("dave");
		assertFalse(registry.containsUser("dave"));
		assertEquals(0, registry.getUserCount());
	}

	@Test
	void testGetAllUsers() {
		registry.registerUser("user1", "user1@example.com");
		registry.registerUser("user2", "user2@example.com");
		var users = registry.getAllUsers();
		assertEquals(2, users.size());
		assertTrue(users.contains("user1"));
		assertTrue(users.contains("user2"));
	}
}
