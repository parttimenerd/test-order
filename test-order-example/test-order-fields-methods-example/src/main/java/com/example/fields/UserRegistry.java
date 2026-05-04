package com.example.fields;

import java.util.*;

/**
 * Manages user data with multiple fields. Tests dependency on field access and
 * modification.
 */
public class UserRegistry {

	private List<String> usernames = new ArrayList<>();
	private Map<String, String> userEmails = new HashMap<>();
	private Map<String, Integer> userScores = new HashMap<>();
	private int nextUserId = 1;

	public void registerUser(String name, String email) {
		usernames.add(name);
		userEmails.put(name, email);
		userScores.put(name, 0);
		nextUserId++;
	}

	public String getEmail(String name) {
		return userEmails.get(name);
	}

	public void setScore(String name, int score) {
		userScores.put(name, score);
	}

	public int getScore(String name) {
		return userScores.getOrDefault(name, 0);
	}

	public int getUserCount() {
		return usernames.size();
	}

	public List<String> getAllUsers() {
		return new ArrayList<>(usernames);
	}

	public boolean containsUser(String name) {
		return usernames.contains(name);
	}

	public void removeUser(String name) {
		usernames.remove(name);
		userEmails.remove(name);
		userScores.remove(name);
	}

	public Map<String, Integer> getTopScores(int limit) {
		return userScores.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit)
				.collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
	}
}
