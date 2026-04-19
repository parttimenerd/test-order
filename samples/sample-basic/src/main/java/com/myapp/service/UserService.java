package com.myapp.service;

import com.myapp.model.User;
import com.myapp.util.Validator;

public class UserService {
    private final Validator validator;

    public UserService() {
        this.validator = new Validator();
    }

    public User createUser(String name, String email, int age) {
        if (!validator.isValidEmail(email)) {
            throw new IllegalArgumentException("Invalid email: " + email);
        }
        if (!validator.isValidName(name)) {
            throw new IllegalArgumentException("Invalid name: " + name);
        }
        return new User(name, email, age);
    }

    public boolean canPurchase(User user) {
        return user.isAdult();
    }
}
