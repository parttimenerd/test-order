package com.example.service.repo;

import java.util.*;

import com.example.service.model.User;

/**
 * In-memory user repository.
 */
public class UserRepository {

	private final Map<String, User> users = new LinkedHashMap<>();

	public void save(User user) {
		users.put(user.getEmail(), user);
	}

	public Optional<User> findByEmail(String email) {
		return Optional.ofNullable(users.get(email));
	}

	public List<User> findAll() {
		return new ArrayList<>(users.values());
	}

	public boolean delete(String email) {
		return users.remove(email) != null;
	}

	public boolean delete(User user) {
		return delete(user.getEmail());
	}

	public int count() {
		return users.size();
	}
}
