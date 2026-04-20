package com.usertest.app;

public class UserValidator {
    public boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    public boolean isValidPassword(String password) {
        return password != null && password.length() >= 8;
    }

    public boolean isValidUsername(String username) {
        return username != null && username.length() >= 3 && username.length() <= 20;
    }
}
