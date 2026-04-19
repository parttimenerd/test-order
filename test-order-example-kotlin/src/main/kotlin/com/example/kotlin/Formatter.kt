package com.example.kotlin

/**
 * Standalone string formatting utilities — no dependency on cart classes.
 */
object Formatter {

    fun formatPrice(amount: Double): String =
        String.format(java.util.Locale.US, "%.2f", amount)

    fun slugify(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    fun truncate(text: String, maxLength: Int): String {
        require(maxLength >= 0) { "maxLength must not be negative" }
        return if (text.length <= maxLength) text
        else text.substring(0, maxLength) + "…"
    }
}
