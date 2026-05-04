package com.example.service.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UserTest {

	@Test
	void createUser() {
		User user = new User("Alice", "alice@example.com");
		assertEquals("Alice", user.getName());
		assertEquals("alice@example.com", user.getEmail());
	}

	@Test
	void setName() {
		User user = new User("Alice", "alice@example.com");
		user.setName("Bob");
		assertEquals("Bob", user.getName());
	}

	@Test
	void setEmail() {
		User user = new User("Alice", "alice@example.com");
		user.setEmail("bob@example.com");
		assertEquals("bob@example.com", user.getEmail());
	}

	@Test
	void toStringContainsFields() {
		User user = new User("Alice", "alice@example.com");
		String s = user.toString();
		assertTrue(s.contains("Alice"));
		assertTrue(s.contains("alice@example.com"));
	}
}
