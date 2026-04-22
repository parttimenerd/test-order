package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class AdvancedFunSpecTest : FunSpec({
    test("Test A: Simple assertion") {
        val result = 1 + 1
        result shouldBe 2
    }

    test("Test B: String operation") {
        val str = "kotest"
        str.length shouldBe 6
    }

    test("Test C: List contains") {
        listOf(1, 2, 3).contains(2) shouldBe true
    }

    test("Test D: Exception handling") {
        shouldThrow<ArithmeticException> {
            1 / 0
        }
    }

    test("Test E: Complex object") {
        data class User(val name: String, val age: Int)
        val user = User("Alice", 30)
        user.age shouldBe 30
    }

    test("Test F: Range check") {
        val range = 1..100
        range.contains(50) shouldBe true
    }

    test("Test G: Map operations") {
        val map = mapOf("a" to 1, "b" to 2)
        map["a"] shouldBe 1
    }

    test("Test H: Filter operation") {
        val filtered = listOf(1, 2, 3, 4, 5).filter { it > 2 }
        filtered.size shouldBe 3
    }
})
