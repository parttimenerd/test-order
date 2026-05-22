package com.example.web;

import com.example.core.UserService;

/**
 * Web controller that depends on UserService from core module.
 */
public class UserController {

    private final UserService userService = new UserService();

    public String getUser(String userId) {
        if (!userService.isValidId(userId)) {
            return "ERROR: invalid id";
        }
        return userService.findUser(userId);
    }
}
