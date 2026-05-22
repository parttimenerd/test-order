package com.example.core;

/**
 * Core service used by the web module.
 */
public class UserService {

    public String findUser(String userId) {
        return "User-" + userId;
    }

    public boolean isValidId(String userId) {
        return userId != null && !userId.isBlank();
    }
}
