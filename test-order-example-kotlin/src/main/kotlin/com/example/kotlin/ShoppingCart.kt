package com.example.kotlin

/**
 * Simple shopping cart that uses [PriceCalculator] for totals.
 */
class ShoppingCart(private val calculator: PriceCalculator = PriceCalculator()) {

    private val items = mutableListOf<CartItem>()

    fun addItem(item: CartItem) {
        items.add(item)
    }

    fun removeItem(name: String): Boolean =
        items.removeIf { it.name == name }

    fun items(): List<CartItem> = items.toList()

    fun itemCount(): Int = items.size

    fun total(): Double = calculator.total(items)

    fun totalWithDiscount(discountPercent: Double): Double =
        calculator.totalWithDiscount(items, discountPercent)

    fun clear() = items.clear()
}
