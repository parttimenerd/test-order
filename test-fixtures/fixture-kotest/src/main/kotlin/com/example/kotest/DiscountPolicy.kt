package com.example.kotest

object DiscountPolicy {
    fun loyaltyDiscount(cents: Int, loyaltyTier: Int): Int {
        val tierBonus = when (loyaltyTier) {
            0 -> 0
            1 -> 100
            2 -> 250
            else -> 400
        }
        return (cents - tierBonus).coerceAtLeast(0)
    }
}
