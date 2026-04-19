package com.myapp.web;

import com.myapp.core.UserService;

public class UserController {
    private final UserService userService = new UserService();

    public String getUser(int id) {
        return "Found: " + userService.lookupName(id);
    }
}
