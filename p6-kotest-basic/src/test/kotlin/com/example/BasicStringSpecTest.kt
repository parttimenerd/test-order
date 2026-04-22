package com.example

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * StringSpec tests - simplest syntax
 */
class BasicStringSpecTest : StringSpec({
    "test 1 - simple assertion" {
        1 shouldBe 1
    }

    "test 2 - string length" {
        "kotest".length shouldBe 6
    }

    "test 3 - list contains" {
        listOf(1, 2, 3).contains(2) shouldBe true
    }

    "test 4 - boolean" {
        true shouldBe true
    }

    "test 5 - range" {
        (1..5).contains(3) shouldBe true
    }
})
