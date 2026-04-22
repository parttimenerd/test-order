package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GradleBasicFunSpecTest : FunSpec({
    test("Test 1 - arithmetic") {
        1 + 1 shouldBe 2
    }

    test("Test 2 - string") {
        "hello".length shouldBe 5
    }

    test("Test 3 - list") {
        listOf(1, 2, 3).size shouldBe 3
    }

    test("Test 4 - boolean") {
        (5 > 3) shouldBe true
    }

    test("Test 5 - range") {
        (1..10).contains(5) shouldBe true
    }
})
