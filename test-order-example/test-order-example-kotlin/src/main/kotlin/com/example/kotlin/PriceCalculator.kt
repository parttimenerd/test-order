package com.example.kotlin

/**
 * Calculates totals for cart items.
 */
class PriceCalculator {

    fun total(items: List<CartItem>): Double =
        items.sumOf { it.price * it.quantity }

    fun totalWithDiscount(items: List<CartItem>, discountPercent: Double): Double {
        require(discountPercent in 0.0..100.0) { "Discount must be between 0 and 100" }
        val subtotal = total(items)
        return subtotal * (1 - discountPercent / 100.0)
    }
}
