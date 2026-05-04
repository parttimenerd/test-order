package com.example.kotlin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests PriceCalculator — depends on PriceCalculator and CartItem.
 */
class PriceCalculatorTest {

    private val calculator = PriceCalculator()

    @Test
    fun totalSingleItem() {
        val items = listOf(CartItem("Widget", 5.0, 4))
        assertEquals(20.0, calculator.total(items), 0.001)
    }

    @Test
    fun totalMultipleItems() {
        val items = listOf(
            CartItem("A", 2.0, 3),
            CartItem("B", 1.5, 2)
        )
        assertEquals(9.0, calculator.total(items), 0.001)
    }

    @Test
    fun totalEmptyList() {
        assertEquals(0.0, calculator.total(emptyList()), 0.001)
    }

    @Test
    fun discountZero() {
        val items = listOf(CartItem("X", 10.0))
        assertEquals(10.0, calculator.totalWithDiscount(items, 0.0), 0.001)
    }

    @Test
    fun discountFull() {
        val items = listOf(CartItem("X", 10.0))
        assertEquals(0.0, calculator.totalWithDiscount(items, 100.0), 0.001)
    }

    @Test
    fun discountInvalid() {
        val items = listOf(CartItem("X", 10.0))
        assertThrows<IllegalArgumentException> {
            calculator.totalWithDiscount(items, 150.0)
        }
    }
}
