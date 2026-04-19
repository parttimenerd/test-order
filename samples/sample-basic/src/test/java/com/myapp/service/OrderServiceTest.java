package com.myapp.service;

import com.myapp.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {
    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService();
    }

    @Test
    void testEmptyCart() {
        assertEquals(0, service.getCartSize());
        assertEquals(0.0, service.getTotal(), 0.001);
    }

    @Test
    void testAddToCart() {
        service.addToCart(new Product("P1", "A", 10.0));
        service.addToCart(new Product("P2", "B", 20.0));
        assertEquals(2, service.getCartSize());
        assertEquals(30.0, service.getTotal(), 0.001);
    }

    @Test
    void testTotalWithDiscount() {
        service.addToCart(new Product("P1", "A", 100.0));
        service.addToCart(new Product("P2", "B", 200.0));
        assertEquals(270.0, service.getTotalWithDiscount(10), 0.001);
    }

    @Test
    void testClearCart() {
        service.addToCart(new Product("P1", "A", 10.0));
        service.clearCart();
        assertEquals(0, service.getCartSize());
    }
}
