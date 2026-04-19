package com.example.circular;

import java.util.HashMap;
import java.util.Map;

/**
 * Circular dependency: InventoryService → OrderService (via callback).
 */
public class InventoryService {

    private final Map<String, Integer> stock = new HashMap<>();
    private OrderService orderService; // set after construction to break cycle

    public void setOrderService(OrderService orderService) {
        this.orderService = orderService;
    }

    public void addStock(String item, int qty) {
        stock.merge(item, qty, Integer::sum);
    }

    public boolean reserve(String item, int qty) {
        int available = stock.getOrDefault(item, 0);
        if (available >= qty) {
            stock.put(item, available - qty);
            if (stock.get(item) == 0 && orderService != null) {
                orderService.onStockDepleted(item);
            }
            return true;
        }
        return false;
    }

    public void release(String item, int qty) {
        stock.merge(item, qty, Integer::sum);
    }

    public int getStock(String item) {
        return stock.getOrDefault(item, 0);
    }
}
