package com.myapp;

/** Validates input data. */
public class Validator {
    public boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= 100;
    }

    public boolean isValidAge(int age) {
        return age >= 0 && age <= 150;
    }
}
