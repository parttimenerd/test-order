package com.example.service.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void createOrder() {
        User user = new User("Alice", "alice@example.com");
        Order order = new Order(1L, user, "Widget", 3);
        assertEquals(1L, order.getId());
        assertEquals(user, order.getUser());
        assertEquals("Widget", order.getProduct());
        assertEquals(3, order.getQuantity());
    }

    @Test
    void totalSingleQuantity() {
        User user = new User("Alice", "alice@example.com");
        Order order = new Order(1L, user, "Widget", 1);
        assertEquals(1, order.total());
    }

    @Test
    void totalMultipleQuantity() {
        User user = new User("Alice", "alice@example.com");
        Order order = new Order(1L, user, "Widget", 5);
        assertEquals(5, order.total());
    }

    @Test
    void toStringContainsFields() {
        User user = new User("Alice", "alice@example.com");
        Order order = new Order(42L, user, "Gadget", 2);
        String s = order.toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("Gadget"));
    }
}
