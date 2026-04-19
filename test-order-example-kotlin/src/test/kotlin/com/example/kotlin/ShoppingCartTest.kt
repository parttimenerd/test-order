package com.example.kotlin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests ShoppingCart — depends on ShoppingCart, PriceCalculator, and CartItem.
 */
class ShoppingCartTest {

    private val cart = ShoppingCart()

    @Test
    fun addAndCount() {
        cart.addItem(CartItem("Apple", 1.50, 3))
        cart.addItem(CartItem("Banana", 0.75, 2))
        assertEquals(2, cart.itemCount())
    }

    @Test
    fun total() {
        cart.addItem(CartItem("Apple", 1.50, 3))
        cart.addItem(CartItem("Banana", 0.75, 2))
        assertEquals(6.0, cart.total(), 0.001)
    }

    @Test
    fun totalWithDiscount() {
        cart.addItem(CartItem("Apple", 10.0, 1))
        assertEquals(9.0, cart.totalWithDiscount(10.0), 0.001)
    }

    @Test
    fun removeItem() {
        cart.addItem(CartItem("Apple", 1.50))
        assertTrue(cart.removeItem("Apple"))
        assertEquals(0, cart.itemCount())
    }

    @Test
    fun removeNonExistent() {
        assertFalse(cart.removeItem("Ghost"))
    }

    @Test
    fun clear() {
        cart.addItem(CartItem("Apple", 1.50))
        cart.clear()
        assertEquals(0, cart.itemCount())
    }

    @Test
    fun emptyCartTotal() {
        assertEquals(0.0, cart.total(), 0.001)
    }
}
