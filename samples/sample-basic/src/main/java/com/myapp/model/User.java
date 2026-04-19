package com.myapp.model;

public class User {
    private String name;
    private String email;
    private int age;

    public User(String name, String email, int age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
    public int getAge() { return age; }

    public boolean isAdult() { return age >= 18; }

    @Override
    public String toString() {
        return name + " <" + email + ">";
    }
}
