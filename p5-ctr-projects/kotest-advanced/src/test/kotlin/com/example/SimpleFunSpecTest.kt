package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Kotest FunSpec - function-style test syntax
 */
class SimpleFunSpecTest : FunSpec({
    test("addition test") {
        (1 + 1) shouldBe 2
    }

    test("string concatenation") {
        "hello" + "world" shouldBe "helloworld"
    }

    test("list contains") {
        listOf(1, 2, 3).contains(2) shouldBe true
    }

    test("negative case") {
        (5 > 3) shouldBe true
    }
})
