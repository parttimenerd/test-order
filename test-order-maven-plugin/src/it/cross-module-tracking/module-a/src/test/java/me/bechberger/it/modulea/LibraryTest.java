package me.bechberger.it.modulea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test for module A's Library class — used to assert that learn discovers it. */
class LibraryTest {
    @Test
    void addsTwoNumbers() {
        assertEquals(3, Library.add(1, 2));
    }
}
