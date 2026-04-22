package com.example

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

class NestedDescribeSpecTest : DescribeSpec({
    describe("Outer describe block 1") {
        it("should test 1.1") {
            1 shouldBe 1
        }
        
        it("should test 1.2") {
            "hello".length shouldBe 5
        }

        describe("Nested describe block 1.1") {
            it("should test 1.1.1") {
                listOf(1).shouldHaveSize(1)
            }
            
            it("should test 1.1.2") {
                val map = mapOf("key" to "value")
                map["key"] shouldBe "value"
            }

            describe("Deeply nested describe block 1.1.1") {
                it("should test 1.1.1.1") {
                    true shouldBe true
                }

                it("should test 1.1.1.2") {
                    (5 > 3) shouldBe true
                }
            }
        }
    }

    describe("Outer describe block 2") {
        it("should test 2.1") {
            2 + 2 shouldBe 4
        }

        describe("Nested describe block 2.1") {
            it("should test 2.1.1") {
                "kotest".uppercase() shouldBe "KOTEST"
            }
        }
    }
})
