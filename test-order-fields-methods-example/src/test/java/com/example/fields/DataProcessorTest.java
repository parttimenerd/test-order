package com.example.fields;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests DataProcessor - depends on static fields.
 * Tests demonstrate static field dependency scoring.
 */
class DataProcessorTest {
    
    @BeforeEach
    void setUp() {
        DataProcessor.resetStats();
    }
    
    @AfterEach
    void tearDown() {
        DataProcessor.resetStats();
    }
    
    @Test
    void testProcessData() {
        DataProcessor.processData("test data");
        assertEquals("test data", DataProcessor.getLastProcessedData());
        assertEquals(1, DataProcessor.getProcessedCount());
    }
    
    @Test
    void testLastProcessTime() {
        long timeBefore = System.currentTimeMillis();
        DataProcessor.processData("data");
        long timeAfter = System.currentTimeMillis();
        
        long lastTime = DataProcessor.getLastProcessTime();
        assertTrue(lastTime >= timeBefore);
        assertTrue(lastTime <= timeAfter);
    }
    
    @Test
    void testProcessCount() {
        DataProcessor.processData("data1");
        DataProcessor.processData("data2");
        DataProcessor.processData("data3");
        assertEquals(3, DataProcessor.getProcessedCount());
        assertEquals("data3", DataProcessor.getLastProcessedData());
    }
    
    @Test
    void testProcessMultiple() {
        int count = DataProcessor.processMultiple("a", "b", "c");
        assertEquals(3, count);
        assertEquals(3, DataProcessor.getProcessedCount());
        assertEquals("c", DataProcessor.getLastProcessedData());
    }
    
    @Test
    void testResetStats() {
        DataProcessor.processData("test");
        assertEquals(1, DataProcessor.getProcessedCount());
        
        DataProcessor.resetStats();
        assertEquals(0, DataProcessor.getProcessedCount());
        assertEquals("", DataProcessor.getLastProcessedData());
    }
}
