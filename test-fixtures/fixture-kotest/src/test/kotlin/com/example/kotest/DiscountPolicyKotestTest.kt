package com.example.kotest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly

class DiscountPolicyKotestTest : StringSpec({
    "tier 0 keeps original price" {
        DiscountPolicy.loyaltyDiscount(1000, 0).shouldBeExactly(1000)
    }

    "tier 1 applies small discount" {
        DiscountPolicy.loyaltyDiscount(1000, 1).shouldBeExactly(900)
    }

    "tier 2 applies medium discount" {
        DiscountPolicy.loyaltyDiscount(1000, 2).shouldBeExactly(750)
    }

    "tier 3 applies high discount" {
        DiscountPolicy.loyaltyDiscount(1000, 3).shouldBeExactly(600)
    }

    "discount never goes below zero" {
        DiscountPolicy.loyaltyDiscount(300, 99).shouldBeExactly(0)
    }
})
