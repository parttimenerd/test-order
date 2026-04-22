package com.example

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Kotest DescribeSpec - BDD-style test syntax
 */
class BddDescribeSpecTest : DescribeSpec({
    describe("calculator") {
        it("should add two numbers") {
            (2 + 3) shouldBe 5
        }

        it("should subtract two numbers") {
            (5 - 3) shouldBe 2
        }

        context("for multiplication") {
            it("should multiply correctly") {
                (3 * 4) shouldBe 12
            }

            it("should handle zero") {
                (5 * 0) shouldBe 0
            }
        }
    }

    describe("string operations") {
        it("should uppercase correctly") {
            "hello".uppercase() shouldBe "HELLO"
        }

        it("should reverse correctly") {
            "hello".reversed() shouldBe "olleh"
        }
    }
})
