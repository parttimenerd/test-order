package com.example.service.repo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.service.model.User;

class UserRepositoryTest {

	private UserRepository repo;

	@BeforeEach
	void setUp() {
		repo = new UserRepository();
	}

	@Test
	void saveAndFindByEmail() {
		User user = new User("Alice", "alice@example.com");
		repo.save(user);
		assertTrue(repo.findByEmail("alice@example.com").isPresent());
		assertEquals("Alice", repo.findByEmail("alice@example.com").get().getName());
	}

	@Test
	void findByEmailNotFound() {
		assertTrue(repo.findByEmail("nobody@example.com").isEmpty());
	}

	@Test
	void findAll() {
		repo.save(new User("Alice", "alice@example.com"));
		repo.save(new User("Bob", "bob@example.com"));
		assertEquals(2, repo.findAll().size());
	}

	@Test
	void count() {
		assertEquals(0, repo.count());
		repo.save(new User("Alice", "alice@example.com"));
		assertEquals(1, repo.count());
	}

	@Test
	void delete() {
		User user = new User("Alice", "alice@example.com");
		repo.save(user);
		repo.delete(user);
		assertEquals(0, repo.count());
	}

	@Test
	void saveOverwritesSameEmail() {
		repo.save(new User("Alice", "alice@example.com"));
		repo.save(new User("Alice2", "alice@example.com"));
		assertEquals(1, repo.count());
		assertEquals("Alice2", repo.findByEmail("alice@example.com").get().getName());
	}
}
