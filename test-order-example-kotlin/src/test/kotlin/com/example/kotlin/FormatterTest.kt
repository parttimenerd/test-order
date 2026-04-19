package com.example.kotlin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests Formatter — depends only on Formatter (no cart classes).
 */
class FormatterTest {

    @Test
    fun formatPrice() {
        assertEquals("9.99", Formatter.formatPrice(9.99))
        assertEquals("10.00", Formatter.formatPrice(10.0))
    }

    @Test
    fun slugify() {
        assertEquals("hello-world", Formatter.slugify("Hello World"))
        assertEquals("foo-bar-baz", Formatter.slugify("  Foo  Bar  Baz  "))
    }

    @Test
    fun slugifySpecialChars() {
        assertEquals("hello-world-123", Formatter.slugify("Hello World 123!"))
    }

    @Test
    fun truncateShort() {
        assertEquals("hi", Formatter.truncate("hi", 10))
    }

    @Test
    fun truncateLong() {
        assertEquals("hel…", Formatter.truncate("hello world", 3))
    }

    @Test
    fun truncateNegativeRejected() {
        assertThrows<IllegalArgumentException> {
            Formatter.truncate("x", -1)
        }
    }
}
