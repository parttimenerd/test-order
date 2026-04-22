package com.example;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LogicServiceTest {
    private LogicService service;
    
    @Before
    public void setup() {
        service = new LogicService();
    }
    
    @Test
    public void testIsPositive() {
        assertTrue(service.isPositive(5));
        assertFalse(service.isPositive(-5));
        assertFalse(service.isPositive(0));
    }
    
    @Test
    public void testIsNegative() {
        assertTrue(service.isNegative(-5));
        assertFalse(service.isNegative(5));
        assertFalse(service.isNegative(0));
    }
    
    @Test
    public void testIsEven() {
        assertTrue(service.isEven(4));
        assertFalse(service.isEven(3));
    }
    
    @Test
    public void testMax() {
        assertEquals(10, service.max(5, 10));
        assertEquals(10, service.max(10, 5));
        assertEquals(5, service.max(5, 5));
    }
    
    @Test
    public void testClassify() {
        assertEquals("positive", service.classify(5));
        assertEquals("negative", service.classify(-5));
        assertEquals("zero", service.classify(0));
    }
}
