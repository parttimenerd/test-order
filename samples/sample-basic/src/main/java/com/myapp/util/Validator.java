package com.myapp.util;

public class Validator {
    public static final int MAX_NAME_LEN = 100;
    public static final String EMAIL_REGEX = ".+@.+\\..+";

    public boolean isValidEmail(String email) {
        if (email == null) return false;
        return email.matches(EMAIL_REGEX);
    }

    public boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME_LEN;
    }

    public boolean isPositive(double value) {
        return value > 0;
    }

    public String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
