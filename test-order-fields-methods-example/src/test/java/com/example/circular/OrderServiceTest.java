package com.example.circular;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the OrderService → InventoryService circular path. */
class OrderServiceTest {

    private OrderService orders;
    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryService();
        orders = new OrderService(inventory);
        inventory.setOrderService(orders);
        inventory.addStock("widget", 10);
    }

    @Test
    void placeOrder() {
        assertTrue(orders.placeOrder("widget", 3));
        assertEquals(7, inventory.getStock("widget"));
    }

    @Test
    void cancelOrder() {
        orders.placeOrder("widget", 3);
        orders.cancelOrder("widget", 3);
        assertEquals(10, inventory.getStock("widget"));
    }

    @Test
    void stockDepletedCallback() {
        // Reserve all stock — triggers onStockDepleted callback
        assertTrue(orders.placeOrder("widget", 10));
        // orderCount: 1 (placeOrder) + 1 (callback) = 2
        assertEquals(2, orders.getOrderCount());
    }
}
