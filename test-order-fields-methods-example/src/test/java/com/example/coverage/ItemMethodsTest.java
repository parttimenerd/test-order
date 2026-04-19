package com.example.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only item-related methods (5 methods touched).
 * Should have moderate coverage score.
 */
class ItemMethodsTest {
    
    private WideCoverageService service;
    
    @BeforeEach
    void setUp() {
        service = new WideCoverageService();
    }
    
    @Test
    void testAddItem() {
        service.addItem("test");
        assertEquals(1, service.getItemCount());
    }
    
    @Test
    void testRemoveItem() {
        service.addItem("item1");
        service.removeItem("item1");
        assertEquals(0, service.getItemCount());
    }
    
    @Test
    void testContainsItem() {
        service.addItem("item1");
        assertTrue(service.containsItem("item1"));
        assertFalse(service.containsItem("item2"));
    }
    
    @Test
    void testGetAllItems() {
        service.addItem("item1");
        service.addItem("item2");
        var items = service.getAllItems();
        assertEquals(2, items.size());
    }
    
    @Test
    void testMultipleItemOperations() {
        service.addItem("a");
        service.addItem("b");
        service.addItem("c");
        assertEquals(3, service.getItemCount());
        assertTrue(service.containsItem("b"));
    }
}
