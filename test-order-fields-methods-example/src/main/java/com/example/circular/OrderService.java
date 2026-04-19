package com.example.circular;

/**
 * Circular dependency: OrderService → InventoryService → OrderService.
 * Tests whether the plugin handles circular dependency graphs without
 * infinite loops or incorrect overlap computation.
 */
public class OrderService {

    private final InventoryService inventory;
    private int orderCount = 0;

    public OrderService(InventoryService inventory) {
        this.inventory = inventory;
    }

    public boolean placeOrder(String item, int qty) {
        if (inventory.reserve(item, qty)) {
            orderCount += 2;
            return true;
        }
        return false;
    }

    public void cancelOrder(String item, int qty) {
        inventory.release(item, qty);
        orderCount--;
    }

    /** Called back by InventoryService when stock drops to zero. */
    public void onStockDepleted(String item) {
        // Auto-reorder logic
        orderCount += 2;
    }

    public int getOrderCount() {
        return orderCount;
    }
}
