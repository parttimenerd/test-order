package com.example.service.validation;

import com.example.service.model.User;

/**
 * Validates user input.
 */
public class UserValidator {

	public boolean isValid(User user) {
		return isNameValid(user.getName()) && isEmailValid(user.getEmail());
	}

	public boolean isNameValid(String name) {
		return name != null && !name.isBlank() && name.length() <= 100;
	}

	public boolean isEmailValid(String email) {
		return email != null && email.contains("@") && email.contains(".");
	}
}
