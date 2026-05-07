package com.myapp.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void createOrder() {
        Order order = new Order("o1", "Alice", List.of("Widget", "Gadget"));
        assertEquals("o1", order.getId());
        assertEquals("Alice", order.getCustomer());
        assertEquals(2, order.itemCount());
    }

    @Test
    void containsItem() {
        Order order = new Order("o2", "Bob", List.of("Phone", "Charger"));
        assertTrue(order.containsItem("Phone"));
        assertFalse(order.containsItem("Laptop"));
    }

    @Test
    void itemsAreImmutable() {
        List<String> items = new java.util.ArrayList<>(List.of("A", "B"));
        Order order = new Order("o3", "Carol", items);
        items.add("C");
        assertEquals(2, order.itemCount());
    }
}
