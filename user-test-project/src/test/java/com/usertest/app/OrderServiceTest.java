package com.usertest.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderService Tests")
class OrderServiceTest {
    private final OrderService orderService = new OrderService();

    @Test
    @DisplayName("Create valid order")
    void testCreateValidOrder() {
        assertTrue(orderService.createOrder("CUST123", 100.0, "1234567890123456"));
        assertTrue(orderService.createOrder("CUST456", 50.0, "5555555555555555"));
    }

    @Test
    @DisplayName("Create invalid order")
    void testCreateInvalidOrder() {
        assertFalse(orderService.createOrder(null, 100.0, "1234567890123456"));
        assertFalse(orderService.createOrder("", 100.0, "1234567890123456"));
        assertFalse(orderService.createOrder("CUST123", -100.0, "1234567890123456"));
        assertFalse(orderService.createOrder("CUST123", 100.0, "invalid"));
    }

    @Test
    @DisplayName("Calculate total with tax")
    void testCalculateTotal() {
        assertEquals(110.0, orderService.calculateTotal(100.0, 0.1), 0.01);
        assertEquals(100.0, orderService.calculateTotal(100.0, 0.0), 0.01);
        assertEquals(107.5, orderService.calculateTotal(100.0, 0.075), 0.01);
    }

    @Test
    @DisplayName("Format order ID")
    void testFormatOrderId() {
        assertEquals("ORD-00000001", orderService.formatOrderId("ORD", 1));
        assertEquals("INV-00001234", orderService.formatOrderId("INV", 1234));
        assertEquals("PO-99999999", orderService.formatOrderId("PO", 99999999));
    }
}
