package com.example

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

/**
 * ShouldSpec tests - BDD style
 */
class BasicShouldSpecTest : ShouldSpec({
    context("Math operations") {
        should("add numbers correctly") {
            2 + 2 shouldBe 4
        }

        should("multiply numbers correctly") {
            3 * 4 shouldBe 12
        }
    }

    context("String operations") {
        should("concatenate strings") {
            "hello " + "world" shouldBe "hello world"
        }

        should("uppercase strings") {
            "test".uppercase() shouldBe "TEST"
        }
    }

    context("Collection operations") {
        should("filter list correctly") {
            listOf(1, 2, 3, 4, 5).filter { it > 2 }.size shouldBe 3
        }
    }
})
