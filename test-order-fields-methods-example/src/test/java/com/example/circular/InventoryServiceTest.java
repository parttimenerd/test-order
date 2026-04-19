package com.example.circular;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises InventoryService directly — the other side of the cycle. */
class InventoryServiceTest {

    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryService();
        inventory.addStock("bolt", 20);
    }

    @Test
    void reserveAndRelease() {
        assertTrue(inventory.reserve("bolt", 5));
        assertEquals(15, inventory.getStock("bolt"));
        inventory.release("bolt", 5);
        assertEquals(20, inventory.getStock("bolt"));
    }

    @Test
    void reserveInsufficientStock() {
        assertFalse(inventory.reserve("bolt", 100));
    }

    @Test
    void addStock() {
        inventory.addStock("nut", 50);
        assertEquals(50, inventory.getStock("nut"));
    }
}
