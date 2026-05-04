package com.example.kotlin

/**
 * A single item in the shopping cart.
 */
data class CartItem(val name: String, val price: Double, val quantity: Int = 1) {
    init {
        require(price >= 0) { "Price must not be negative" }
        require(quantity > 0) { "Quantity must be positive" }
    }
}
