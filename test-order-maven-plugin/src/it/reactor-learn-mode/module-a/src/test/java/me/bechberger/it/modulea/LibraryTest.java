package me.bechberger.it.modulea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LibraryTest {
    @Test
    void addWorks() {
        assertEquals(7, Library.add(3, 4));
    }
}
