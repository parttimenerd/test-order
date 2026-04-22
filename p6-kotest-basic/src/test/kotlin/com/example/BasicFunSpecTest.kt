package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Basic FunSpec tests for bug hunting
 */
class BasicFunSpecTest : FunSpec({
    test("Test A - should pass") {
        1 + 1 shouldBe 2
    }

    test("Test B - string operation") {
        "hello".length shouldBe 5
    }

    test("Test C - list operation") {
        listOf(1, 2, 3).size shouldBe 3
    }

    test("Test D - boolean operation") {
        (5 > 3) shouldBe true
    }

    test("Test E - map operation") {
        mapOf("key" to "value").containsKey("key") shouldBe true
    }
})
