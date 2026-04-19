package com.myapp;

/** Formats user-facing messages. */
public class MessageFormatter {
    private final Greeter greeter;

    public MessageFormatter(Greeter greeter) {
        this.greeter = greeter;
    }

    public String welcome(String name) {
        return greeter.greet(name) + " Welcome aboard!";
    }

    public String farewell(String name) {
        return "Goodbye, " + name + "!";
    }
}
