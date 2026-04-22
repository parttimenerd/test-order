package com.example

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class GradleBasicShouldSpecTest : ShouldSpec({
    context("Arithmetic") {
        should("add correctly") {
            2 + 2 shouldBe 4
        }

        should("multiply correctly") {
            3 * 4 shouldBe 12
        }
    }

    context("String") {
        should("concat") {
            "hello" + "world" shouldBe "helloworld"
        }

        should("uppercase") {
            "test".uppercase() shouldBe "TEST"
        }
    }

    context("List") {
        should("filter") {
            listOf(1, 2, 3, 4, 5).filter { it > 2 }.size shouldBe 3
        }

        should("map") {
            listOf(1, 2, 3).map { it * 2 } shouldBe listOf(2, 4, 6)
        }
    }
})
