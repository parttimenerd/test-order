package com.example.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests all method categories (item, metadata, and validation).
 * Should have highest coverage score (touches most methods).
 */
class CombinedMethodsTest {
    
    private WideCoverageService service;
    
    @BeforeEach
    void setUp() {
        service = new WideCoverageService();
    }
    
    @Test
    void testCompleteWorkflow() {
        // Exercise item methods
        service.addItem("item1");
        assertTrue(service.containsItem("item1"));
        
        // Exercise metadata methods
        service.setMetadata("source", "test");
        assertEquals("test", service.getMetadata("source"));
        
        // Exercise validation methods
        assertTrue(service.validate());
        
        // Mixed access count
        int count = service.getAccessCount();
        assertTrue(count > 0);
    }
    
    @Test
    void testItemWithMetadata() {
        service.addItem("task1");
        service.setMetadata("priority", "high");
        assertEquals(1, service.getItemCount());
        assertEquals("high", service.getMetadata("priority"));
    }
    
    @Test
    void testFullClear() {
        service.addItem("item1");
        service.setMetadata("key1", "value1");
        service.clear();
        assertEquals(0, service.getItemCount());
        assertNull(service.getMetadata("key1"));
    }
    
    @Test
    void testAccessTracking() {
        service.addItem("item");      // accessCount becomes 1
        service.getItemCount();        // accessCount becomes 2
        service.setMetadata("key", "val"); // accessCount becomes 3
        service.getMetadata("key");   // accessCount becomes 4
        service.validate();           // accessCount becomes 5
        int count = service.getAccessCount(); // accessCount becomes 6, returns 6
        assertTrue(count > 4);
    }
}
