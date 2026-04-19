package com.example.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests validation and utility methods only (3 methods).
 * Should have lowest coverage score.
 */
class ValidationMethodsTest {
    
    private WideCoverageService service;
    
    @BeforeEach
    void setUp() {
        service = new WideCoverageService();
    }
    
    @Test
    void testValidateEmpty() {
        assertFalse(service.validate());
    }
    
    @Test
    void testValidateWithItems() {
        service.addItem("test");
        assertTrue(service.validate());
    }
    
    @Test
    void testClear() {
        service.addItem("item1");
        service.addItem("item2");
        service.setMetadata("key", "value");
        service.clear();
        assertEquals(0, service.getItemCount());
    }
}
