package com.myapp.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrderControllerTest {
    @Test
    void handleReturnsOkPrefix() {
        assertTrue(new OrderController().handle("1", "x").startsWith("OK:"));
    }
}
