package com.example.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Cart — depends on Cart and Product.
 */
class CartTest {

    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = new Cart();
    }

    @Test
    void emptyCartHasZeroTotal() {
        assertEquals(0.0, cart.total(), 0.001);
        assertEquals(0, cart.size());
    }

    @Test
    void addProducts() {
        cart.add(new Product("A", 10.0));
        cart.add(new Product("B", 20.0));
        assertEquals(2, cart.size());
        assertEquals(30.0, cart.total(), 0.001);
    }

    @Test
    void removeProduct() {
        Product p = new Product("X", 5.0);
        cart.add(p);
        cart.remove(p);
        assertEquals(0, cart.size());
    }

    @Test
    void clearCart() {
        cart.add(new Product("A", 1.0));
        cart.add(new Product("B", 2.0));
        cart.clear();
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void itemsAreUnmodifiable() {
        cart.add(new Product("Z", 99.0));
        assertThrows(UnsupportedOperationException.class, () -> cart.getItems().add(new Product("hack", 0)));
    }
}
