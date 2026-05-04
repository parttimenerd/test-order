package com.example.service.service;

import java.util.List;
import java.util.Optional;

import com.example.service.model.User;
import com.example.service.repo.UserRepository;
import com.example.service.validation.UserValidator;

/**
 * Service layer for user operations.
 */
public class UserService {

	private final UserRepository repository;
	private final UserValidator validator;

	public UserService(UserRepository repository, UserValidator validator) {
		this.repository = repository;
		this.validator = validator;
	}

	public User register(String name, String email) {
		User user = new User(name, email);
		if (!validator.isValid(user)) {
			throw new IllegalArgumentException("Invalid user data");
		}
		Optional<User> existing = repository.findByEmail(email);
		if (existing.isPresent()) {
			throw new IllegalStateException("User with email " + email + " already exists");
		}
		repository.save(user);
		return user;
	}

	public Optional<User> findByEmail(String email) {
		return repository.findByEmail(email);
	}

	public List<User> listAll() {
		return repository.findAll();
	}
}
