package com.example.service.validation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.example.service.model.User;

class UserValidatorTest {

	private final UserValidator validator = new UserValidator();

	@Test
	void validUser() {
		assertTrue(validator.isValid(new User("Alice", "alice@example.com")));
	}

	@Test
	void nullNameInvalid() {
		assertFalse(validator.isValid(new User(null, "alice@example.com")));
	}

	@Test
	void emptyNameInvalid() {
		assertFalse(validator.isValid(new User("", "alice@example.com")));
	}

	@Test
	void blankNameInvalid() {
		assertFalse(validator.isValid(new User("   ", "alice@example.com")));
	}

	@Test
	void nullEmailInvalid() {
		assertFalse(validator.isValid(new User("Alice", null)));
	}

	@Test
	void emailWithoutAtInvalid() {
		assertFalse(validator.isValid(new User("Alice", "alice-example.com")));
	}

	@Test
	void nameValidation() {
		assertTrue(validator.isNameValid("Alice"));
		assertFalse(validator.isNameValid(""));
		assertFalse(validator.isNameValid(null));
	}

	@Test
	void emailValidation() {
		assertTrue(validator.isEmailValid("alice@example.com"));
		assertFalse(validator.isEmailValid("bad"));
		assertFalse(validator.isEmailValid(null));
	}
}
