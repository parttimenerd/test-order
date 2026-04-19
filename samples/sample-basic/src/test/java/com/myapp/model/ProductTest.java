package com.myapp.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductTest {
    @Test
    void testCreateProduct() {
        Product p = new Product("P1", "Widget", 9.99);
        assertEquals("P1", p.getId());
        assertEquals("Widget", p.getName());
        assertEquals(9.99, p.getPrice(), 0.001);
    }

    @Test
    void testApplyDiscount() {
        Product p = new Product("P1", "Widget", 100.0);
        assertEquals(90.0, p.applyDiscount(10), 0.001);
        assertEquals(50.0, p.applyDiscount(50), 0.001);
    }

    @Test
    void testInvalidDiscount() {
        Product p = new Product("P1", "Widget", 100.0);
        assertThrows(IllegalArgumentException.class, () -> p.applyDiscount(-5));
        assertThrows(IllegalArgumentException.class, () -> p.applyDiscount(101));
    }
}
