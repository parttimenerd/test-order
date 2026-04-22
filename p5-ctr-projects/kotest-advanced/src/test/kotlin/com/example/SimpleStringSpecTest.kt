package com.example

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Kotest StringSpec - simple string-based test syntax
 */
class SimpleStringSpecTest : StringSpec({
    "integer test 1" {
        (1 + 1) shouldBe 2
    }

    "integer test 2" {
        (2 + 2) shouldBe 4
    }

    "string test" {
        "kotlin".length shouldBe 6
    }

    "boolean test" {
        true shouldBe true
    }

    "list test" {
        listOf(1, 2, 3).size shouldBe 3
    }
})
