package com.example.kotlin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests CartItem — depends only on CartItem.
 */
class CartItemTest {

    @Test
    fun creation() {
        val item = CartItem("Apple", 1.50, 3)
        assertEquals("Apple", item.name)
        assertEquals(1.50, item.price, 0.001)
        assertEquals(3, item.quantity)
    }

    @Test
    fun defaultQuantity() {
        val item = CartItem("Banana", 0.75)
        assertEquals(1, item.quantity)
    }

    @Test
    fun negativePriceRejected() {
        assertThrows<IllegalArgumentException> {
            CartItem("Bad", -1.0)
        }
    }

    @Test
    fun zeroQuantityRejected() {
        assertThrows<IllegalArgumentException> {
            CartItem("Bad", 1.0, 0)
        }
    }

    @Test
    fun dataClassEquality() {
        assertEquals(CartItem("A", 1.0, 2), CartItem("A", 1.0, 2))
        assertNotEquals(CartItem("A", 1.0), CartItem("B", 1.0))
    }
}
