package com.myapp.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderAnnotationTest {
    static int sequence = 0;
    
    @Test
    @Order(1)
    void testFirst() {
        sequence = 1;
        assertEquals(1, sequence);
    }
    
    @Test
    @Order(2)
    void testSecond() {
        assertEquals(1, sequence);
        sequence = 2;
    }
    
    @Test
    @Order(3)
    void testThird() {
        assertEquals(2, sequence);
        sequence = 3;
    }
}
